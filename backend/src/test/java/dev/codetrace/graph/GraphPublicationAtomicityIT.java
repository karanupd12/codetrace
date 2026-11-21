package dev.codetrace.graph;

import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphWriteRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.NodeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.repository.GraphSnapshotRepository.Snapshot;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.model.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Concurrency guarantee behind "no partial publication": while a large snapshot is being written and published,
 * a concurrent reader must only ever see nothing, or the complete graph, and never a PUBLISHED snapshot whose rows are
 * missing or incomplete. A write that fails midway must leave no rows at all.
 */
@Testcontainers
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GraphPublicationAtomicityIT {
    private static final int NODES = 30_000;
    private static final int EDGES = 60_000;

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
    @Autowired SourceRepositoryRepository repos;
    @Autowired JdbcTemplate jdbc;

    private Snapshot newSnapshot(String commit) {
        UUID repo = repos.insert(UUID.randomUUID(), SourceType.GIT_URL, "https://github.com/o/" + UUID.randomUUID(), "o/r").id();
        return snapshots.createOrGet(repo, commit, "p1", "b1", null).snapshot();
    }

    /** A synthetic graph: NODES file nodes in a ring-ish chain with EDGES resolved edges between them. */
    private static Result syntheticGraph(UUID snapshotId) {
        List<Node> nodes = new ArrayList<>(NODES);
        for (int i = 0; i < NODES; i++) {
            UUID id = UUID.nameUUIDFromBytes((snapshotId + "|n|" + i).getBytes(StandardCharsets.UTF_8));
            nodes.add(new Node(id, NodeType.FILE, "f" + i, "", "f" + i, "", "f" + i, 0, 0, ""));
        }
        List<Edge> edges = new ArrayList<>(EDGES);
        for (int i = 0; i < EDGES; i++) {
            UUID src = nodes.get(i % NODES).id();
            UUID dst = nodes.get((i * 7 + 1) % NODES).id();
            EdgeType type = i < NODES ? EdgeType.REFERENCES : EdgeType.IMPORTS;
            edges.add(new Edge(src, type, dst, "", Resolution.RESOLVED, 1.0, "TEST", "", "", "f", i, 1));
        }
        return new Result(nodes, edges, new TreeMap<>());
    }

    @Test
    void aConcurrentReaderNeverSeesAPartialOrHalfPublishedGraph() throws Exception {
        Snapshot snap = newSnapshot("a".repeat(40));
        Result graph = syntheticGraph(snap.id());
        assertThat(graph.edges().stream().map(e -> e.srcId() + "|" + e.type() + "|" + e.dstId()).distinct().count())
                .as("synthetic edges must be unique").isEqualTo(EDGES);

        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger sawEmpty = new AtomicInteger();
        AtomicInteger sawPublished = new AtomicInteger();
        List<String> violations = new CopyOnWriteArrayList<>();

        Thread reader = new Thread(() -> {
            while (!done.get()) {
                // status first, rows second: if PUBLISHED was visible, the rows must already be complete
                String status = jdbc.queryForObject("select status from graph_snapshot where id = ?", String.class, snap.id());
                long nodes = jdbc.queryForObject("select count(*) from code_node where snapshot_id = ?", Long.class, snap.id());
                long edges = jdbc.queryForObject("select count(*) from dependency_edge where snapshot_id = ?", Long.class, snap.id());
                samples.incrementAndGet();
                if (nodes == 0) sawEmpty.incrementAndGet();
                if (nodes != 0 && nodes != NODES) violations.add("partial nodes: " + nodes + " (status " + status + ")");
                if (edges != 0 && edges != EDGES) violations.add("partial edges: " + edges + " (status " + status + ")");
                if ("PUBLISHED".equals(status)) {
                    sawPublished.incrementAndGet();
                    if (nodes != NODES || edges != EDGES) {
                        violations.add("PUBLISHED but incomplete: " + nodes + "/" + edges);
                    }
                }
            }
        }, "atomicity-reader");
        reader.start();

        assertThat(snapshots.begin(snap.id())).isTrue();
        writer.write(snap, graph);
        assertThat(jdbc.queryForObject("select status from graph_snapshot where id = ?", String.class, snap.id()))
                .isEqualTo("VALIDATING");
        assertThat(snapshots.publish(snap.id())).isTrue();
        // keep reading a little after publication so the PUBLISHED branch is exercised too
        Thread.sleep(300);
        done.set(true);
        reader.join(10_000);

        assertThat(violations).isEmpty();
        assertThat(samples.get()).as("reader actually ran concurrently with the write").isGreaterThan(10);
        assertThat(sawEmpty.get()).as("reader observed the pre-publication empty state").isPositive();
        assertThat(sawPublished.get()).as("reader observed the published state").isPositive();

        Snapshot published = snapshots.find(snap.id()).orElseThrow();
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.nodeCount()).isEqualTo(NODES);
        assertThat(published.edgeCount()).isEqualTo(EDGES);
        assertThat(published.resolvedEdgeCount()).isEqualTo(EDGES);
    }

    @Test
    void aWriteThatFailsMidwayLeavesNoRowsAtAll() {
        Snapshot snap = newSnapshot("b".repeat(40));
        Result good = syntheticGraph(snap.id());
        List<Edge> edges = new ArrayList<>(good.edges());
        UUID missingTarget = UUID.randomUUID(); // not a node of this snapshot: violates the composite foreign key
        edges.add(new Edge(good.nodes().get(0).id(), EdgeType.REFERENCES, missingTarget, "", Resolution.RESOLVED, 1.0, "TEST",
                "", "", "f", 1, 1));
        Result broken = new Result(good.nodes(), edges, Map.of());

        assertThat(snapshots.begin(snap.id())).isTrue();
        assertThatThrownBy(() -> writer.write(snap, broken)).hasMessageContaining("fk_edge_dst");

        assertThat(jdbc.queryForObject("select count(*) from code_node where snapshot_id = ?", Long.class, snap.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from dependency_edge where snapshot_id = ?", Long.class, snap.id())).isZero();
        assertThat(snapshots.find(snap.id()).orElseThrow().status()).isEqualTo("BUILDING"); // never reached VALIDATING
        assertThat(snapshots.publish(snap.id())).as("cannot publish a snapshot that never validated").isFalse();
    }

    @Test
    void aFailedSnapshotCanBeRebuiltFromScratchButAPublishedOneCannot() {
        Snapshot snap = newSnapshot("c".repeat(40));
        Result graph = syntheticGraph(snap.id());
        assertThat(snapshots.begin(snap.id())).isTrue();
        writer.write(snap, graph);
        snapshots.markFailed(snap.id(), "validation failed");

        assertThat(jdbc.queryForObject("select count(*) from code_node where snapshot_id = ?", Long.class, snap.id())).isZero();
        Snapshot failed = snapshots.find(snap.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("validation failed");

        assertThat(snapshots.begin(snap.id())).as("a failed snapshot may be rebuilt").isTrue();
        long t0 = System.nanoTime();
        writer.write(snap, graph); // no concurrent reader here, so this is the writer's own throughput
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("GRAPH-WRITE-TIMING: %d nodes + %d edges in %d ms (%d rows/s)%n", NODES, EDGES, ms,
                (NODES + EDGES) * 1000L / Math.max(1, ms));
        assertThat(snapshots.publish(snap.id())).isTrue();

        assertThat(snapshots.begin(snap.id())).as("a published snapshot is never rebuilt").isFalse();
        snapshots.markFailed(snap.id(), "too late"); // must be a no-op
        assertThat(snapshots.find(snap.id()).orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from code_node where snapshot_id = ?", Long.class, snap.id()))
                .isEqualTo(NODES);
    }
}
