package dev.codetrace.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.NodeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.graph.repository.GraphIntegrityRepository;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphWriteRepository;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The integrity checks run against stored rows: a well-formed graph passes and each kind of violation is counted. */
@Testcontainers
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GraphIntegrityIT {

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
    }

    @Autowired GraphSnapshotRepository snapshots;
    @Autowired GraphWriteRepository writer;
    @Autowired GraphIntegrityRepository validator;
    @Autowired SourceRepositoryRepository repos;

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    private UUID node(NodeType type, String qualifiedName) {
        UUID id = UUID.randomUUID();
        nodes.add(new Node(id, type, qualifiedName, "", qualifiedName, "", "src/p/A.java", 1, 1, ""));
        return id;
    }

    private void edge(UUID src, EdgeType type, UUID dst, Resolution resolution, String group) {
        double confidence = resolution == Resolution.AMBIGUOUS ? 0.45 : resolution == Resolution.RESOLVED ? 1.0 : 0.0;
        edges.add(new Edge(src, type, dst, dst == null ? "x/0" : "", resolution, confidence, "TEST", "", group,
                "src/p/A.java", 1, 1));
    }

    private void resolved(UUID src, EdgeType type, UUID dst) {
        edge(src, type, dst, Resolution.RESOLVED, "");
    }

    private Snapshot staged() {
        UUID repo = repos.insert(UUID.randomUUID(), SourceType.GIT_URL, "https://github.com/o/" + UUID.randomUUID(), "o/r").id();
        Snapshot snapshot = snapshots.createOrGet(repo, "c".repeat(40), "p1", "b1", null).snapshot();
        assertThat(snapshots.begin(snapshot.id())).isTrue();
        writer.write(snapshot, new Result(nodes, edges, new TreeMap<>()));
        return snapshot;
    }

    private UUID[] wellFormedGraph() {
        UUID pkg = node(NodeType.PACKAGE, "p");
        UUID file = node(NodeType.FILE, "src/p/A.java");
        UUID type = node(NodeType.TYPE, "p.A");
        UUID m = node(NodeType.METHOD, "p.A.m");
        UUID n = node(NodeType.METHOD, "p.A.n");
        UUID field = node(NodeType.FIELD, "p.A.f");
        resolved(pkg, EdgeType.CONTAINS, file);
        resolved(file, EdgeType.CONTAINS, type);
        resolved(type, EdgeType.CONTAINS, m);
        resolved(type, EdgeType.CONTAINS, n);
        resolved(type, EdgeType.CONTAINS, field);
        resolved(file, EdgeType.IMPORTS, type);
        resolved(m, EdgeType.REFERENCES, type);
        edge(type, EdgeType.EXTENDS, null, Resolution.UNRESOLVED, "");
        edge(m, EdgeType.CALLS, null, Resolution.UNRESOLVED, "");
        edge(m, EdgeType.CALLS, m, Resolution.AMBIGUOUS, "g1");
        edge(m, EdgeType.CALLS, n, Resolution.AMBIGUOUS, "g1");
        return new UUID[] {pkg, file, type, m, n};
    }

    @Test
    void aWellFormedGraphHasNoViolationsAfterItsStatisticsAreRefreshed() {
        wellFormedGraph();
        Snapshot snapshot = staged();

        writer.refreshStatistics();

        assertThat(validator.validate(snapshot.id(), nodes.size(), edges.size())).isEmpty();
    }

    @Test
    void eachKindOfViolationIsCounted() {
        UUID[] g = wellFormedGraph();
        UUID file = g[1], type = g[2], m = g[3], n = g[4];
        node(NodeType.METHOD, "p.A.orphan");                         // declaration without a container
        UUID other = node(NodeType.TYPE, "p.B");
        resolved(file, EdgeType.CONTAINS, other);
        UUID shared = node(NodeType.METHOD, "p.B.k");                 // declaration with two containers
        resolved(type, EdgeType.CONTAINS, shared);
        resolved(other, EdgeType.CONTAINS, shared);
        UUID otherPackage = node(NodeType.PACKAGE, "q");              // file with two containers
        resolved(otherPackage, EdgeType.CONTAINS, file);
        resolved(file, EdgeType.CALLS, m);                            // a file cannot be a caller
        edge(n, EdgeType.CALLS, m, Resolution.AMBIGUOUS, "solo");     // ambiguous group with one candidate
        Snapshot snapshot = staged();

        assertThat(validator.validate(snapshot.id(), nodes.size(), edges.size())).containsExactly(
                "declarations without exactly one container (2)",
                "files with more than one container (1)",
                "ambiguous alternative groups with fewer than two candidates (1)",
                "edges whose endpoint node types do not fit the edge type (1)");
    }
}
