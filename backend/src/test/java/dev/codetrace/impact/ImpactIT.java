package dev.codetrace.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.change.ChangeDetector;
import dev.codetrace.change.ChangeSet;
import dev.codetrace.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.RiskAssessor.Level;
import dev.codetrace.impact.RiskAssessor.RiskAssessment;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/**
 * Impact analysis on a real Git history of a small two-module shop, run through the real index and graph pipeline.
 * Each scenario has hand-labelled ground truth (which symbols a reviewer would say are affected); the analyzer's
 * answer is compared to it as precision and recall.
 */
@Testcontainers
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImpactAnalyzerIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static Path root;
    static Path repoDir;
    static final List<String> commits = new ArrayList<>(); // 0 base, 1 tax body, 2 total signature, 3 delete Unrelated, 4 price body
    static final Map<String, UUID> snapshots = new LinkedHashMap<>();
    static UUID repoId;

    private static final String PRICING = """
            package shop;

            public class Pricing {
                public double tax(double amount) {
                    return amount * 0.2;
                }

                public double total(double amount) {
                    return amount + tax(amount);
                }

                public double discount(double amount) {
                    return amount * 0.9;
                }
            }
            """;
    private static final String ORDER = """
            package shop;

            public class Order {
                private final Pricing pricing = new Pricing();

                public double price(double amount) {
                    return pricing.total(amount);
                }
            }
            """;
    private static final String UNRELATED = """
            package shop;

            public class Unrelated {
                public String label() {
                    return "unrelated";
                }
            }
            """;
    private static final String CHECKOUT = """
            package shopweb;

            import shop.Order;

            public class Checkout {
                private final Order order = new Order();

                public double pay(double amount) {
                    return order.price(amount);
                }
            }
            """;
    private static final String CHECKOUT_TEST = """
            package shopweb;

            public class CheckoutTest {
                public void paysTheTotal() {
                    Checkout checkout = new Checkout();
                    checkout.pay(10.0);
                }
            }
            """;
    private static final String CHAIN = """
            package shopweb;

            import shop.Order;

            public class Chain {
                public Order make() {
                    return new Order();
                }

                public double viaChain() {
                    return make().price(1.0);
                }
            }
            """;

    private static Map<String, Entry> files(String pricing, String order, boolean withUnrelated) {
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put("core/src/main/java/shop/Pricing.java", Entry.file(pricing));
        m.put("core/src/main/java/shop/Order.java", Entry.file(order));
        if (withUnrelated) m.put("core/src/main/java/shop/Unrelated.java", Entry.file(UNRELATED));
        m.put("web/src/main/java/shopweb/Checkout.java", Entry.file(CHECKOUT));
        m.put("web/src/main/java/shopweb/Chain.java", Entry.file(CHAIN));
        m.put("web/src/test/java/shopweb/CheckoutTest.java", Entry.file(CHECKOUT_TEST));
        return m;
    }

    static synchronized void createRepo() throws Exception {
        if (root != null) return;
        root = Files.createTempDirectory("codetrace-impact-it-").toRealPath();
        repoDir = Files.createDirectories(root.resolve("shop"));
        String taxChanged = PRICING.replace("amount * 0.2", "amount * 0.25");
        String totalReshaped = taxChanged.replace("total(double amount) {\n        return amount + tax(amount);",
                "total(double amount, int quantity) {\n        return amount * quantity + tax(amount);");
        String priceChanged = ORDER.replace("pricing.total(amount)", "pricing.total(amount) + 0");
        commits.add(GitFixtures.createRepo(repoDir, files(PRICING, ORDER, true)));
        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).build()) {
            commits.add(GitFixtures.commit(repo, files(taxChanged, ORDER, true), commits.get(0), "tax rate"));
            commits.add(GitFixtures.commit(repo, files(totalReshaped, ORDER, true), commits.get(1), "total takes quantity"));
            commits.add(GitFixtures.commit(repo, files(totalReshaped, ORDER, false), commits.get(2), "drop Unrelated"));
            commits.add(GitFixtures.commit(repo, files(totalReshaped, priceChanged, false), commits.get(3), "price tweak"));
        }
    }

    @BeforeAll
    static void beforeAll() throws Exception {
        createRepo();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path workspace = Files.createTempDirectory("codetrace-impact-it-ws-").toRealPath();
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
    @Autowired ChangeDetector detector;
    @Autowired ImpactAnalyzer analyzer;
    @Autowired RiskAssessor assessor;

    // ---- helpers ------------------------------------------------------------------------------------------------

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> res = rest.getForEntity(path, String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(path + " -> " + res.getBody()).isTrue();
        return json.readTree(res.getBody());
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, h), String.class);
    }

    private void awaitJob(String jobId) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).until(() -> {
            JsonNode j = get("/api/v1/index-jobs/" + jobId);
            String s = j.get("status").asText();
            if (s.equals("FAILED") || s.equals("DEAD_LETTERED")) throw new AssertionError("job failed: " + j);
            return s.equals("SUCCEEDED");
        });
    }

    private ImpactAnalysis analyze(int baseIdx, int headIdx) throws Exception {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).setMustExist(true).build()) {
            ChangeSet cs = detector.detect(repo, commits.get(baseIdx), commits.get(headIdx));
            return analyzer.analyze(snapshots.get(commits.get(baseIdx)), snapshots.get(commits.get(headIdx)), cs, null);
        }
    }

    private static Set<String> names(List<AffectedSymbol> affected) {
        return affected.stream().map(AffectedSymbol::qualifiedName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String kinds(List<ChangedEntry> changed) {
        return changed.stream().map(c -> c.kind() + " " + c.any().qualifiedName())
                .sorted().collect(Collectors.joining("; "));
    }

    /** Precision and recall of the reported set against the labelled one. */
    private static double[] score(Set<String> actual, Set<String> labelled) {
        long tp = actual.stream().filter(labelled::contains).count();
        double precision = actual.isEmpty() ? 1.0 : (double) tp / actual.size();
        double recall = labelled.isEmpty() ? 1.0 : (double) tp / labelled.size();
        return new double[] {precision, recall};
    }

    private void report(String scenario, Set<String> actual, Set<String> labelled) {
        double[] pr = score(actual, labelled);
        System.out.printf("IMPACT-EVAL %s precision=%.2f recall=%.2f (reported %d, labelled %d)%n",
                scenario, pr[0], pr[1], actual.size(), labelled.size());
        assertThat(pr[0]).as(scenario + " precision, reported=" + actual).isEqualTo(1.0);
        assertThat(pr[1]).as(scenario + " recall, reported=" + actual).isEqualTo(1.0);
    }

    // ---- pipeline ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void indexAndGraphEveryRevision() throws Exception {
        ResponseEntity<String> reg = post("/api/v1/repositories",
                json.writeValueAsString(Map.of("sourceType", "LOCAL", "location", repoDir.toString())));
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = json.readTree(reg.getBody());
        awaitJob(body.at("/syncJob/id").asText());
        repoId = UUID.fromString(body.at("/repository/id").asText());

        for (String commit : commits) {
            ResponseEntity<String> ix = post("/api/v1/repositories/" + repoId + "/index-jobs", "{\"commitHash\":\"" + commit + "\"}");
            assertThat(ix.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            awaitJob(json.readTree(ix.getBody()).get("id").asText());
            ResponseEntity<String> g = post("/api/v1/repositories/" + repoId + "/graph-snapshots", "{\"commitHash\":\"" + commit + "\"}");
            assertThat(g.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode gb = json.readTree(g.getBody());
            String snapshot = gb.at("/snapshot/id").asText();
            awaitJob(gb.at("/job/id").asText());
            assertThat(get("/api/v1/snapshots/" + snapshot).get("status").asText()).isEqualTo("PUBLISHED");
            snapshots.put(commit, UUID.fromString(snapshot));
        }
        assertThat(snapshots).hasSize(5);
    }

    // ---- labelled scenarios -----------------------------------------------------------------------------------

    @Test
    @Order(2)
    void changingAMethodBodyAffectsEveryTransitiveCallerIncludingTheTest() throws Exception {
        ImpactAnalysis a = analyze(0, 1);

        assertThat(kinds(a.changed())).isEqualTo("MODIFIED shop.Pricing#tax(double)");
        Set<String> labelled = Set.of("shop.Pricing#total(double)", "shop.Order#price(double)", "shopweb.Checkout#pay(double)",
                "shopweb.CheckoutTest#paysTheTotal()");
        report("body-change", names(a.affected()), labelled);

        AffectedSymbol test = a.affected().stream().filter(x -> x.qualifiedName().contains("CheckoutTest")).findFirst().orElseThrow();
        assertThat(test.test()).isTrue();
        assertThat(test.distance()).isEqualTo(4);
        assertThat(test.certain()).isTrue();
        assertThat(test.chain()).extracting(h -> h.from() + " -> " + h.to()).containsExactly(
                "shopweb.CheckoutTest#paysTheTotal() -> shopweb.Checkout#pay(double)",
                "shopweb.Checkout#pay(double) -> shop.Order#price(double)",
                "shop.Order#price(double) -> shop.Pricing#total(double)",
                "shop.Pricing#total(double) -> shop.Pricing#tax(double)");
        assertThat(test.chain()).allSatisfy(h -> assertThat(h.path()).isNotBlank());
        assertThat(a.truncated()).isFalse();
        assertThat(a.affected()).extracting(AffectedSymbol::module).contains("core", "web");

        RiskAssessment risk = assessor.assess(a);
        assertThat(risk.level()).isIn(Level.LOW, Level.MEDIUM);
        assertThat(risk.factors()).anySatisfy(f -> {
            assertThat(f.signal()).isEqualTo("06-test-coverage");
            assertThat(f.points()).isZero(); // a test depends on it
        });
    }

    @Test
    @Order(3)
    void changingASignatureRemovesTheOldSymbolAndWarnsAboutItsCallers() throws Exception {
        ImpactAnalysis a = analyze(1, 2);

        assertThat(kinds(a.changed())).contains("REMOVED shop.Pricing#total(double)")
                .contains("ADDED shop.Pricing#total(double,int)").doesNotContain("shop.Pricing#tax");
        Set<String> labelled = Set.of("shop.Order#price(double)", "shopweb.Checkout#pay(double)", "shopweb.CheckoutTest#paysTheTotal()");
        report("signature-change", names(a.affected()), labelled);

        ChangedEntry removed = a.changed().stream().filter(c -> c.kind() == Kind.REMOVED).findFirst().orElseThrow();
        assertThat(removed.directDependents()).isEqualTo(1);
        assertThat(assessor.assess(a).factors()).anySatisfy(f -> {
            assertThat(f.signal()).isEqualTo("03-breaking-api");
            assertThat(f.points()).isEqualTo(20);
        });
    }

    @Test
    @Order(4)
    void deletingUnusedCodeHasNoBlastRadius() throws Exception {
        ImpactAnalysis a = analyze(2, 3);

        assertThat(kinds(a.changed())).isEqualTo("REMOVED shop.Unrelated; REMOVED shop.Unrelated#label()");
        report("unused-deletion", names(a.affected()), Set.of());
        assertThat(a.changed()).allMatch(c -> c.directDependents() == 0);
        assertThat(assessor.assess(a).level()).isEqualTo(Level.LOW);
    }

    @Test
    @Order(5)
    void callsWithAnUnknownReceiverAreReportedAsPossibleNotAsAffected() throws Exception {
        ImpactAnalysis a = analyze(3, 4);

        assertThat(kinds(a.changed())).isEqualTo("MODIFIED shop.Order#price(double)");
        report("possible-dependents", names(a.affected()), Set.of("shopweb.Checkout#pay(double)", "shopweb.CheckoutTest#paysTheTotal()"));
        assertThat(a.possible()).singleElement().satisfies(p -> {
            assertThat(p.qualifiedName()).isEqualTo("shopweb.Chain#viaChain()");
            assertThat(p.changedSymbol()).isEqualTo("shop.Order#price(double)");
            assertThat(p.path()).endsWith("Chain.java");
            assertThat(p.line()).isPositive();
        });
        assertThat(names(a.affected())).doesNotContain("shopweb.Chain#viaChain()");
        assertThat(assessor.assess(a).factors()).anySatisfy(f -> {
            assertThat(f.signal()).isEqualTo("05-uncertainty");
            assertThat(f.points()).isEqualTo(5);
        });
    }

    @Test
    @Order(6)
    void theAnalysisIsDeterministic() throws Exception {
        assertThat(analyze(0, 1)).isEqualTo(analyze(0, 1));
        assertThat(analyze(3, 4)).isEqualTo(analyze(3, 4));
    }

    @Test
    @Order(7)
    void aDepthOfOneStopsAtDirectCallers() throws Exception {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).setMustExist(true).build()) {
            ChangeSet cs = detector.detect(repo, commits.get(0), commits.get(1));
            ImpactAnalysis a = analyzer.analyze(snapshots.get(commits.get(0)), snapshots.get(commits.get(1)), cs, 1);

            assertThat(names(a.affected())).containsExactly("shop.Pricing#total(double)");
            assertThat(a.depth()).isEqualTo(1);
        }
    }
}
