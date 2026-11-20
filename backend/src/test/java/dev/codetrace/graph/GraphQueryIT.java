package dev.codetrace.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.graph.GraphModel.Edge;
import dev.codetrace.graph.GraphModel.EdgeType;
import dev.codetrace.graph.GraphModel.Node;
import dev.codetrace.graph.GraphModel.NodeType;
import dev.codetrace.graph.GraphModel.Resolution;
import dev.codetrace.graph.GraphModel.Result;
import dev.codetrace.graph.GraphSnapshotDao.Snapshot;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Query API over a hand-built graph where every expected answer can be derived by hand:
 *
 * <pre>
 *   A -> B -> D -> F          A -> C -> D          (two shortest A..F routes)
 *   B -> A, D -> B            cycles
 *   D -> F also as REFERENCES parallel edge of another type
 *   A -> ext.Lib#x            UNRESOLVED (no target node)
 *   G -> H, G -> H2           AMBIGUOUS alternatives (one group)
 *   E                         isolated
 *   chain.N0 -> ... -> N29    long chain (depth caps)
 *   star.S -> star.L0..L699   wide fan-out (result caps)
 * </pre>
 */
@Testcontainers
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphQueryIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("codetrace.graph.max-path-visited", () -> "50"); // small, so truncation is easy to provoke
    }

    @Autowired TestRestTemplate rest;
    @Autowired GraphSnapshotDao snapshots;
    @Autowired GraphWriter writer;
    @Autowired RepositoryDao repos;
    @Autowired ObjectMapper json;

    static UUID snap;
    static UUID unpublished;
    static final Map<String, Node> N = new TreeMap<>();

    private static Node node(String qn) {
        return N.computeIfAbsent(qn, k -> new Node(UUID.nameUUIDFromBytes(("q|" + k).getBytes(StandardCharsets.UTF_8)),
                NodeType.METHOD, k, "", k.substring(k.lastIndexOf('.') + 1), "", "f.java", 1, 2, ""));
    }

    private static Edge edge(String a, String b, EdgeType t, Resolution res, double conf, String group) {
        return new Edge(node(a).id(), t, b == null ? null : node(b).id(), "", res, conf, "TEST", "", group, "f.java", 7, 1);
    }

    private static Edge call(String a, String b) {
        return edge(a, b, EdgeType.CALLS, Resolution.RESOLVED, 1.0, "");
    }

    @BeforeAll
    static void declareNodes() {
        for (String s : List.of("m.A", "m.B", "m.C", "m.D", "m.E", "m.F", "m.G", "m.H", "m.H2")) node(s);
        for (int i = 0; i < 30; i++) node("chain.N" + i);
        node("star.S");
        for (int i = 0; i < 700; i++) node("star.L" + i);
    }

    private Snapshot newSnapshot(String commit) {
        UUID repo = repos.insert(UUID.randomUUID(), SourceType.GIT_URL, "https://github.com/o/" + UUID.randomUUID(), "o/r").id();
        return snapshots.createOrGet(repo, commit, "p1", "b1", null).snapshot();
    }

    private void ensureGraph() {
        if (snap != null) return;
        List<Edge> edges = new ArrayList<>(List.of(
                call("m.A", "m.B"), call("m.A", "m.C"), call("m.B", "m.D"), call("m.C", "m.D"), call("m.D", "m.F"),
                call("m.B", "m.A"), call("m.D", "m.B"),
                edge("m.D", "m.F", EdgeType.REFERENCES, Resolution.RESOLVED, 1.0, ""),
                new Edge(node("m.A").id(), EdgeType.CALLS, null, "ext.Lib#x", Resolution.UNRESOLVED, 0.0, "TEST",
                        "NOT_DECLARED_IN_REPOSITORY", "", "f.java", 9, 1),
                edge("m.G", "m.H", EdgeType.CALLS, Resolution.AMBIGUOUS, 0.45, "g1"),
                edge("m.G", "m.H2", EdgeType.CALLS, Resolution.AMBIGUOUS, 0.45, "g1")));
        for (int i = 0; i < 29; i++) edges.add(call("chain.N" + i, "chain.N" + (i + 1)));
        for (int i = 0; i < 700; i++) edges.add(call("star.S", "star.L" + i));

        Snapshot s = newSnapshot("a".repeat(40));
        Result graph = new Result(new ArrayList<>(N.values()), edges, new TreeMap<>());
        snapshots.begin(s.id());
        writer.write(s, graph);
        snapshots.publish(s.id());
        snap = s.id();
        unpublished = newSnapshot("b".repeat(40)).id();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private String url(String path) {
        ensureGraph();
        return path.replace("{s}", snap.toString());
    }

    private JsonNode get(String path, Object... vars) throws Exception {
        ResponseEntity<String> res = rest.getForEntity(url(path), String.class, vars);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(path + " -> " + res.getBody()).isTrue();
        return json.readTree(res.getBody());
    }

    private HttpStatus status(String path) {
        return HttpStatus.valueOf(rest.getForEntity(url(path), String.class).getStatusCode().value());
    }

    private static String id(String qn) {
        return node(qn).id().toString();
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    // ---- nodes -----------------------------------------------------------------------------------------------------

    @Test
    void searchesPagesAndFiltersNodes() throws Exception {
        JsonNode chain = get("/api/v1/snapshots/{s}/nodes?q=chain.&limit=10");
        assertThat(chain.get("total").asInt()).isEqualTo(30);
        assertThat(chain.get("items")).hasSize(10);

        JsonNode lastPage = get("/api/v1/snapshots/{s}/nodes?q=chain.&limit=10&offset=25");
        assertThat(lastPage.get("items")).hasSize(5);

        assertThat(get("/api/v1/snapshots/{s}/nodes?q=M.A").get("total").asInt()).isEqualTo(1);          // case-insensitive
        assertThat(get("/api/v1/snapshots/{s}/nodes?type=TYPE").get("total").asInt()).isZero();
        assertThat(get("/api/v1/snapshots/{s}/nodes?type=method&q=star.l1&limit=5").get("total").asInt()).isEqualTo(111);
        assertThat(get("/api/v1/snapshots/{s}/nodes?q=%25").get("total").asInt()).isZero();               // LIKE chars are literal
        assertThat(get("/api/v1/snapshots/{s}/nodes?limit=100000").get("limit").asInt()).isEqualTo(500);  // capped
        assertThat(get("/api/v1/snapshots/{s}/nodes?limit=1&q=m.a").get("items").get(0).get("qualifiedName").asText())
                .isEqualTo("m.A");
        assertThat(status("/api/v1/snapshots/{s}/nodes?type=BANANA")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nodeDetailReportsDegreesByTypeAndResolution() throws Exception {
        JsonNode a = get("/api/v1/snapshots/{s}/nodes/" + id("m.A"));

        assertThat(a.at("/node/qualifiedName").asText()).isEqualTo("m.A");
        assertThat(a.at("/node/extractionMethod").asText()).isNotBlank();
        Map<String, Long> out = new TreeMap<>();
        a.get("outgoing").forEach(d -> out.put(d.get("edgeType").asText() + "/" + d.get("resolution").asText(), d.get("count").asLong()));
        assertThat(out).containsEntry("CALLS/RESOLVED", 2L).containsEntry("CALLS/UNRESOLVED", 1L);
        assertThat(a.get("incoming")).hasSize(1); // B -> A
        assertThat(status("/api/v1/snapshots/{s}/nodes/" + UUID.randomUUID())).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- edges -----------------------------------------------------------------------------------------------------

    @Test
    void edgesCarryProvenanceAndRespectFilters() throws Exception {
        JsonNode out = get("/api/v1/snapshots/{s}/nodes/" + id("m.A") + "/edges?direction=OUT&types=CALLS");

        assertThat(out.get("total").asInt()).isEqualTo(3);
        JsonNode unresolved = null;
        for (JsonNode e : out.get("items")) if ("UNRESOLVED".equals(e.get("resolution").asText())) unresolved = e;
        assertThat(unresolved).isNotNull();
        assertThat(unresolved.get("dstId").isNull()).isTrue();
        assertThat(unresolved.get("dstName").asText()).isEqualTo("ext.Lib#x");
        assertThat(unresolved.get("reason").asText()).isEqualTo("NOT_DECLARED_IN_REPOSITORY");
        assertThat(unresolved.get("evidencePath").asText()).isEqualTo("f.java");
        assertThat(unresolved.get("evidenceLine").asInt()).isEqualTo(9);
        assertThat(unresolved.get("resolverVersion").asText()).isEqualTo(GraphBuilder.VERSION);

        assertThat(get("/api/v1/snapshots/{s}/nodes/" + id("m.A") + "/edges?direction=OUT&states=RESOLVED").get("total").asInt())
                .isEqualTo(2);
        assertThat(names(get("/api/v1/snapshots/{s}/nodes/" + id("m.A") + "/edges?direction=IN").get("items"), "srcName"))
                .containsExactly("m.B");
        assertThat(get("/api/v1/snapshots/{s}/nodes/" + id("m.A") + "/edges?direction=BOTH").get("total").asInt()).isEqualTo(4);
        assertThat(get("/api/v1/snapshots/{s}/nodes/" + id("star.S") + "/edges?limit=100000").get("items")).hasSize(500);
        assertThat(status("/api/v1/snapshots/{s}/nodes/" + id("m.A") + "/edges?types=NOPE")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/v1/snapshots/{s}/nodes/" + id("m.A") + "/edges?direction=SIDEWAYS")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- neighbourhood -----------------------------------------------------------------------------------------------

    @Test
    void neighbourhoodGrowsOneHopAtATimeAndNeverRepeatsAnEdge() throws Exception {
        JsonNode d1 = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&depth=1");
        assertThat(names(d1.get("nodes"), "qualifiedName")).containsExactlyInAnyOrder("m.A", "m.B", "m.C");
        assertThat(d1.get("edges")).hasSize(4); // A->B, A->C, B->A, A->ext (unresolved)
        assertThat(d1.get("truncated").asBoolean()).isFalse();

        JsonNode d2 = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&depth=2");
        assertThat(names(d2.get("nodes"), "qualifiedName")).contains("m.D").doesNotContain("m.F");

        JsonNode d3 = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&depth=3&types=CALLS,REFERENCES");
        assertThat(names(d3.get("nodes"), "qualifiedName")).contains("m.F");

        JsonNode deep = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&depth=99");
        assertThat(deep.get("depth").asInt()).isEqualTo(6); // capped; cycles A<->B and D->B did not loop
        List<Long> ids = new ArrayList<>();
        deep.get("edges").forEach(e -> ids.add(e.get("id").asLong()));
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    }

    @Test
    void neighbourhoodFiltersByDirectionAndState() throws Exception {
        JsonNode resolvedOnly = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&states=RESOLVED");
        assertThat(resolvedOnly.get("edges")).hasSize(3);
        assertThat(names(resolvedOnly.get("edges"), "resolution")).containsOnly("RESOLVED");

        JsonNode out = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&direction=OUT");
        assertThat(out.get("edges")).hasSize(3);                     // A->B, A->C, A->ext
        JsonNode in = get("/api/v1/snapshots/{s}/graph?center=" + id("m.A") + "&direction=IN");
        assertThat(names(in.get("edges"), "srcName")).containsExactly("m.B");

        JsonNode withParallel = get("/api/v1/snapshots/{s}/graph?center=" + id("m.F") + "&direction=IN&types=REFERENCES");
        assertThat(names(withParallel.get("edges"), "edgeType")).containsExactly("REFERENCES");
    }

    @Test
    void wideNeighbourhoodsAreTruncatedNotUnbounded() throws Exception {
        JsonNode dflt = get("/api/v1/snapshots/{s}/graph?center=" + id("star.S"));
        assertThat(dflt.get("edges")).hasSize(200);
        assertThat(dflt.get("truncated").asBoolean()).isTrue();

        JsonNode max = get("/api/v1/snapshots/{s}/graph?center=" + id("star.S") + "&limit=100000");
        assertThat(max.get("edges")).hasSize(500);                    // hard cap, 700 exist
        assertThat(max.get("truncated").asBoolean()).isTrue();
        assertThat(max.get("nodes").size()).isLessThanOrEqualTo(501);
    }

    // ---- paths -----------------------------------------------------------------------------------------------------

    @Test
    void findsEveryShortestRouteAndOrdersEdgesFromSourceToTarget() throws Exception {
        JsonNode r = get("/api/v1/snapshots/{s}/paths?from=" + id("m.A") + "&to=" + id("m.F") + "&types=CALLS");

        assertThat(r.get("found").asBoolean()).isTrue();
        assertThat(r.get("shortestLength").asInt()).isEqualTo(3);
        assertThat(r.get("paths")).hasSize(2);
        List<String> routes = new ArrayList<>();
        for (JsonNode p : r.get("paths")) {
            JsonNode es = p.get("edges");
            assertThat(es).hasSize(3);
            assertThat(es.get(0).get("srcName").asText()).isEqualTo("m.A");
            assertThat(es.get(2).get("dstName").asText()).isEqualTo("m.F");
            for (int i = 1; i < es.size(); i++) {
                assertThat(es.get(i).get("srcName").asText()).isEqualTo(es.get(i - 1).get("dstName").asText()); // contiguous
            }
            assertThat(p.get("certain").asBoolean()).isTrue();
            assertThat(p.get("minConfidence").asDouble()).isEqualTo(1.0);
            routes.add(es.get(0).get("dstName").asText());
        }
        assertThat(routes).containsExactlyInAnyOrder("m.B", "m.C");

        assertThat(get("/api/v1/snapshots/{s}/paths?from=" + id("m.A") + "&to=" + id("m.F") + "&types=CALLS&limit=1")
                .get("paths")).hasSize(1);
        // with the parallel REFERENCES edge allowed, there are two ways to take the last hop as well
        assertThat(get("/api/v1/snapshots/{s}/paths?from=" + id("m.A") + "&to=" + id("m.F") + "&types=CALLS,REFERENCES&limit=20")
                .get("paths")).hasSize(4);
    }

    @Test
    void followsEdgesBackwardsForDependents() throws Exception {
        JsonNode r = get("/api/v1/snapshots/{s}/paths?from=" + id("m.F") + "&to=" + id("m.A") + "&direction=IN&types=CALLS");

        assertThat(r.get("found").asBoolean()).isTrue();
        assertThat(r.get("shortestLength").asInt()).isEqualTo(3);
        JsonNode first = r.get("paths").get(0).get("edges");
        assertThat(first.get(0).get("dstName").asText()).isEqualTo("m.F");   // starts at F, walking toward callers
        assertThat(first.get(2).get("srcName").asText()).isEqualTo("m.A");
    }

    @Test
    void cyclesTerminateAndTrivialCasesAreHandled() throws Exception {
        JsonNode back = get("/api/v1/snapshots/{s}/paths?from=" + id("m.B") + "&to=" + id("m.A"));
        assertThat(back.get("shortestLength").asInt()).isEqualTo(1);                       // B -> A directly, cycle ignored

        JsonNode self = get("/api/v1/snapshots/{s}/paths?from=" + id("m.B") + "&to=" + id("m.B"));
        assertThat(self.get("found").asBoolean()).isTrue();
        assertThat(self.get("shortestLength").asInt()).isZero();
        assertThat(self.at("/paths/0/edges")).isEmpty();

        JsonNode none = get("/api/v1/snapshots/{s}/paths?from=" + id("m.A") + "&to=" + id("m.E"));
        assertThat(none.get("found").asBoolean()).isFalse();
        assertThat(none.get("truncated").asBoolean()).isFalse();
        assertThat(none.get("paths")).isEmpty();
    }

    @Test
    void ambiguousHopsAreTraversableButNeverCertain() throws Exception {
        JsonNode r = get("/api/v1/snapshots/{s}/paths?from=" + id("m.G") + "&to=" + id("m.H"));

        assertThat(r.get("found").asBoolean()).isTrue();
        JsonNode p = r.get("paths").get(0);
        assertThat(p.get("certain").asBoolean()).isFalse();
        assertThat(p.get("minConfidence").asDouble()).isEqualTo(0.45);
        assertThat(get("/api/v1/snapshots/{s}/paths?from=" + id("m.G") + "&to=" + id("m.H") + "&states=RESOLVED")
                .get("found").asBoolean()).isFalse();
    }

    @Test
    void pathDepthIsCappedAndUnresolvedEdgesAreNeverTraversed() throws Exception {
        assertThat(get("/api/v1/snapshots/{s}/paths?from=" + id("chain.N0") + "&to=" + id("chain.N10") + "&maxDepth=100")
                .get("shortestLength").asInt()).isEqualTo(10);
        JsonNode tooFar = get("/api/v1/snapshots/{s}/paths?from=" + id("chain.N0") + "&to=" + id("chain.N11") + "&maxDepth=100");
        assertThat(tooFar.get("found").asBoolean()).isFalse();
        assertThat(tooFar.get("maxDepth").asInt()).isEqualTo(10);                          // the server-side cap
        assertThat(get("/api/v1/snapshots/{s}/paths?from=" + id("chain.N0") + "&to=" + id("chain.N5") + "&maxDepth=3")
                .get("found").asBoolean()).isFalse();
    }

    @Test
    void searchesThatExceedTheVisitedBudgetSayTheyWereTruncated() throws Exception {
        JsonNode r = get("/api/v1/snapshots/{s}/paths?from=" + id("star.S") + "&to=" + id("m.E"));

        assertThat(r.get("found").asBoolean()).isFalse();
        assertThat(r.get("truncated").asBoolean()).isTrue();
    }

    // ---- guards ------------------------------------------------------------------------------------------------------

    @Test
    void invalidRequestsAreRejected() {
        assertThat(status("/api/v1/snapshots/{s}/paths?from=" + id("m.A") + "&to=" + UUID.randomUUID())).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/api/v1/snapshots/{s}/paths?from=" + id("m.A"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/v1/snapshots/{s}/paths?from=" + id("m.A") + "&to=" + id("m.B") + "&direction=BOTH"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/v1/snapshots/{s}/graph?center=" + UUID.randomUUID())).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/api/v1/snapshots/{s}/graph")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/v1/snapshots/{s}/graph?center=not-a-uuid")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyPublishedSnapshotsCanBeRead() {
        ensureGraph();
        assertThat(rest.getForEntity("/api/v1/snapshots/" + unpublished + "/nodes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.getForEntity("/api/v1/snapshots/" + unpublished + "/graph?center=" + id("m.A"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.getForEntity("/api/v1/snapshots/" + UUID.randomUUID() + "/nodes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nodesFromAnotherSnapshotAreInvisible() throws Exception {
        ensureGraph();
        // the same node id exists in no other snapshot's graph: asking the unpublished one is a 409, never data leakage
        assertThat(rest.getForEntity("/api/v1/snapshots/" + unpublished + "/nodes/" + id("m.A"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
