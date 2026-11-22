package dev.codetrace.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Two commits indexed and graphed through the API, then compared: added, removed and changed nodes and edges. */
@Testcontainers
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphComparisonIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static Path root;
    static Path repoDir;
    static String first;
    static String second;

    static synchronized void createRepo() throws Exception {
        if (root != null) return;
        root = Files.createTempDirectory("codetrace-compare-it-").toRealPath();
        repoDir = Files.createDirectories(root.resolve("app"));
        first = GitFixtures.createRepo(repoDir, Map.of("src/p/A.java",
                Entry.file("package p; public class A { void a() { b(); } void b() {} void gone() {} }")));
        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).setMustExist(true).build()) {
            second = GitFixtures.commit(repo, Map.of("src/p/A.java",
                    Entry.file("package p; public class A { public void a() { c(); } void b() {} void c() {} }")), first, "second");
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path workspace = Files.createTempDirectory("codetrace-compare-it-ws-").toRealPath();
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("codetrace.ingestion.local-roots", () -> {
            try {
                createRepo();
                return root.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        r.add("codetrace.ingestion.workspace-dir", workspace::toString);
        r.add("codetrace.worker.sweep-ms", () -> "500");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> res = rest.getForEntity(path, String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(path + " -> " + res.getBody()).isTrue();
        return json.readTree(res.getBody());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.postForEntity(path, new HttpEntity<>(json.writeValueAsString(body), h), String.class);
        assertThat(res.getStatusCode()).as(path + " -> " + res.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        return json.readTree(res.getBody());
    }

    private void awaitJob(String jobId) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> "SUCCEEDED".equals(get("/api/v1/index-jobs/" + jobId).get("status").asText()));
    }

    private String graphOf(UUID repoId, String commit) throws Exception {
        awaitJob(post("/api/v1/repositories/" + repoId + "/index-jobs", Map.of("commitHash", commit)).get("id").asText());
        JsonNode build = post("/api/v1/repositories/" + repoId + "/graph-snapshots", Map.of("commitHash", commit));
        awaitJob(build.at("/job/id").asText());
        return build.at("/snapshot/id").asText();
    }

    private static List<String> lines(JsonNode items, String... fields) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : items) {
            List<String> parts = new ArrayList<>();
            for (String f : fields) parts.add(item.get(f).asText());
            out.add(String.join(" ", parts));
        }
        return out;
    }

    @Test
    void reportsAddedRemovedAndChangedNodesAndEdges() throws Exception {
        JsonNode registered = post("/api/v1/repositories", Map.of("sourceType", "LOCAL", "location", repoDir.toString()));
        awaitJob(registered.at("/syncJob/id").asText());
        UUID repoId = UUID.fromString(registered.at("/repository/id").asText());
        String base = graphOf(repoId, first);
        String head = graphOf(repoId, second);
        String compare = "/api/v1/snapshots/" + base + "/compare/" + head;

        JsonNode summary = get(compare);
        assertThat(summary.at("/base/commitHash").asText()).isEqualTo(first);
        assertThat(summary.at("/head/commitHash").asText()).isEqualTo(second);
        assertThat(lines(summary.get("nodes"), "change", "kind", "count"))
                .containsExactly("ADDED METHOD 1", "CHANGED METHOD 1", "REMOVED METHOD 1");

        assertThat(lines(get(compare + "/nodes").get("items"), "change", "qualifiedName", "baseModifiers", "headModifiers"))
                .containsExactly("ADDED p.A#c() null ", "CHANGED p.A#a()  public", "REMOVED p.A#gone()  null");

        JsonNode calls = get(compare + "/edges?types=CALLS");
        assertThat(lines(calls.get("items"), "change", "srcName", "dstName", "resolutionState"))
                .containsExactly("ADDED p.A#a() p.A#c() RESOLVED", "REMOVED p.A#a() p.A#b() RESOLVED");
        assertThat(get(compare + "/edges?types=CONTAINS&change=removed").get("total").asLong()).isEqualTo(1);

        assertThat(get("/api/v1/snapshots/" + head + "/compare/" + head).get("nodes")).isEmpty();
        assertThat(rest.getForEntity(compare + "/nodes?change=MOVED", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/snapshots/" + base + "/compare/" + UUID.randomUUID(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
