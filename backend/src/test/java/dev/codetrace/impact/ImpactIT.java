package dev.codetrace.impact;

import dev.codetrace.impact.model.ImpactAnalysis;
import dev.codetrace.impact.risk.RiskAssessor;
import dev.codetrace.impact.service.ImpactAnalyzer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.impact.change.ChangeDetector;
import dev.codetrace.impact.change.ChangeSet;
import dev.codetrace.impact.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.model.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.model.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.risk.RiskAssessor.Level;
import dev.codetrace.impact.risk.RiskAssessor.RiskAssessment;
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
class ImpactIT {

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
    /** Named after Pricing but never calls it: only the naming convention links it to Pricing. */
    private static final String PRICING_TEST = """
            package shop;

            public class PricingTest {
                public void ratesArePositive() {
                }
            }
            """;
    /** An HTTP entry point whose handler reaches the pricing code through Checkout. */
    private static final String CHECKOUT_CONTROLLER = """
            package shopweb;

            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class CheckoutController {
                @PostMapping("/checkout")
                public double checkout(double amount) {
                    return new Checkout().pay(amount);
                }
            }
            """;
    private static final String GATEWAY = """
            package shop;

            public interface Gateway {
                double charge(double amount);
            }
            """;
    private static final String CARD_GATEWAY = """
            package shop;

            public class CardGateway implements Gateway {
                @Override
                public double charge(double amount) {
                    return amount;
                }
            }
            """;
    /** A concrete base method: its callers most likely run its own body, so a subclass override is not followed. */
    private static final String BASE_FEE = """
            package shop;

            public class BaseFee {
                public double fee(double amount) {
                    return 0.0;
                }
            }
            """;
    private static final String CARD_FEE = """
            package shop;

            public class CardFee extends BaseFee {
                @Override
                public double fee(double amount) {
                    return 1.0;
                }
            }
            """;
    /** Calls the gateway through the interface: only override linking connects it to CardGateway. */
    private static final String PAYMENTS = """
            package shopweb;

            import shop.BaseFee;
            import shop.CardGateway;
            import shop.Gateway;

            public class Payments {
                private final Gateway gateway = new CardGateway();
                private final BaseFee fees = new BaseFee();

                public double settle(double amount) {
                    return gateway.charge(amount);
                }

                public double fee(double amount) {
                    return fees.fee(amount);
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
        m.put("web/src/main/java/shopweb/CheckoutController.java", Entry.file(CHECKOUT_CONTROLLER));
        m.put("core/src/test/java/shop/PricingTest.java", Entry.file(PRICING_TEST));
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
            // a commit that is never indexed or graphed, for the "build the graph first" precondition
            commits.add(GitFixtures.commit(repo, files(totalReshaped, priceChanged.replace("+ 0", "+ 1"), false), commits.get(4), "never graphed"));
            // commits 6 and 7: a payment gateway interface whose implementation changes (override linking)
            Map<String, Entry> withGateway = new LinkedHashMap<>(files(totalReshaped, priceChanged, false));
            withGateway.put("core/src/main/java/shop/Gateway.java", Entry.file(GATEWAY));
            withGateway.put("core/src/main/java/shop/CardGateway.java", Entry.file(CARD_GATEWAY));
            withGateway.put("web/src/main/java/shopweb/Payments.java", Entry.file(PAYMENTS));
            withGateway.put("core/src/main/java/shop/BaseFee.java", Entry.file(BASE_FEE));
            withGateway.put("core/src/main/java/shop/CardFee.java", Entry.file(CARD_FEE));
            // a widely implemented interface: its callers are not followed, and the report says so
            withGateway.put("core/src/main/java/shop/Discount.java", Entry.file("package shop;\npublic interface Discount {\n    double apply(double amount);\n}\n"));
            for (int i = 1; i <= 6; i++) {
                withGateway.put("core/src/main/java/shop/Discount" + i + ".java", Entry.file("package shop;\npublic class Discount" + i
                        + " implements Discount {\n    public double apply(double amount) {\n        return amount;\n    }\n}\n"));
            }
            withGateway.put("web/src/main/java/shopweb/Basket.java", Entry.file("package shopweb;\nimport shop.Discount;\nimport shop.Discount1;\n"
                    + "public class Basket {\n    private final Discount discount = new Discount1();\n    public double total(double amount) {\n"
                    + "        return discount.apply(amount);\n    }\n}\n"));
            commits.add(GitFixtures.commit(repo, withGateway, commits.get(5), "payments"));
            Map<String, Entry> feeChanged = new LinkedHashMap<>(withGateway);
            feeChanged.put("core/src/main/java/shop/CardGateway.java", Entry.file(CARD_GATEWAY.replace("return amount;", "return amount * 1.02;")));
            feeChanged.put("core/src/main/java/shop/CardFee.java", Entry.file(CARD_FEE.replace("return 1.0;", "return 1.5;")));
            feeChanged.put("core/src/main/java/shop/Discount3.java", Entry.file("package shop;\npublic class Discount3 implements Discount {\n"
                    + "    public double apply(double amount) {\n        return amount * 0.9;\n    }\n}\n"));
            commits.add(GitFixtures.commit(repo, feeChanged, commits.get(6), "card fee"));
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    static UUID reportId;
    static UUID reportJobId;

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

    /** Indexes and graphs one commit through the API and records its snapshot. */
    private void prepare(String commit) throws Exception {
        ResponseEntity<String> ix = post("/api/v1/repositories/" + repoId + "/index-jobs", "{\"commitHash\":\"" + commit + "\"}");
        assertThat(ix.getStatusCode()).as(ix.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        awaitJob(json.readTree(ix.getBody()).get("id").asText());
        JsonNode gb = json.readTree(post("/api/v1/repositories/" + repoId + "/graph-snapshots", "{\"commitHash\":\"" + commit + "\"}").getBody());
        awaitJob(gb.at("/job/id").asText());
        snapshots.put(commit, UUID.fromString(gb.at("/snapshot/id").asText()));
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

        for (String commit : commits.subList(0, 5)) { // the sixth commit stays unbuilt on purpose
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
                "shopweb.CheckoutTest#paysTheTotal()", "shop.PricingTest", "shopweb.CheckoutController#checkout(double)");
        report("body-change", names(a.affected()), labelled);

        assertThat(a.endpoints()).extracting(e -> e.change() + " " + e.label() + " " + e.handler() + " " + e.distance())
                .containsExactly("AFFECTED POST /checkout shopweb.CheckoutController#checkout(double) 4");

        AffectedSymbol named = a.affected().stream().filter(x -> x.qualifiedName().equals("shop.PricingTest")).findFirst().orElseThrow();
        assertThat(named.test()).isTrue();
        assertThat(named.confidence()).isEqualTo(0.8);
        assertThat(named.chain()).extracting(h -> h.from() + " " + h.edgeType() + " " + h.to()).containsExactly(
                "shop.PricingTest TESTS shop.Pricing", "shop.Pricing CONTAINS shop.Pricing#tax(double)");

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
        Set<String> labelled = Set.of("shop.Order#price(double)", "shopweb.Checkout#pay(double)", "shopweb.CheckoutTest#paysTheTotal()",
                "shop.PricingTest", "shopweb.CheckoutController#checkout(double)");
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
        report("possible-dependents", names(a.affected()), Set.of("shopweb.Checkout#pay(double)", "shopweb.CheckoutTest#paysTheTotal()",
                "shopweb.CheckoutController#checkout(double)"));
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

            // the test named after the changed class is listed whatever the depth
            assertThat(names(a.affected())).containsExactly("shop.Pricing#total(double)", "shop.PricingTest");
            assertThat(a.depth()).isEqualTo(1);
        }
    }

    // ---- API ----------------------------------------------------------------------------------------------------

    private String body(int base, int head) throws Exception {
        return json.writeValueAsString(Map.of("baseCommit", commits.get(base), "headCommit", commits.get(head)));
    }

    private String reports() {
        return "/api/v1/repositories/" + repoId + "/impact-reports";
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    @Order(8)
    void invalidRequestsAreRejectedBeforeAnyWork() throws Exception {
        assertThat(post(reports(), "{\"baseCommit\":\"nope\",\"headCommit\":\"" + commits.get(1) + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(reports(), "{\"headCommit\":\"" + commits.get(1) + "\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(reports(), body(1, 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // same revision twice
        assertThat(post(reports(), "{\"baseCommit\":\"" + commits.get(0) + "\",\"headCommit\":\"" + commits.get(1)
                + "\",\"depth\":99}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(reports(), "{\"baseCommit\":\"" + "e".repeat(40) + "\",\"headCommit\":\"" + commits.get(1) + "\"}")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/repositories/" + UUID.randomUUID() + "/impact-reports", body(0, 1)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("select count(*) from impact_report")).isZero();
    }

    @Test
    @Order(9)
    void aRevisionWithoutAPublishedGraphCannotBeAnalysed() throws Exception {
        ResponseEntity<String> head = post(reports(), body(4, 5));
        ResponseEntity<String> base = post(reports(), body(5, 4));

        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(head.getBody()).contains("Build the dependency graph for revision " + commits.get(5).substring(0, 7)).contains("head");
        assertThat(base.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(base.getBody()).contains("(base)");
        assertThat(count("select count(*) from impact_report")).isZero();
    }

    @Test
    @Order(11)
    void everyJobReportsItsStepsInOrderInPlainWords() throws Exception {
        UUID indexJob = jdbc.queryForObject("select id from index_job where operation = 'INDEX' and commit_hash = ?", UUID.class,
                commits.get(0));
        UUID graphJob = jdbc.queryForObject("select id from index_job where operation = 'GRAPH_BUILD' and commit_hash = ?",
                UUID.class, commits.get(0));

        assertThat(steps(indexJob)).containsExactly("READING", "READ", "SAVING", "DONE");
        assertThat(steps(graphJob)).containsExactly("LOADING", "BUILDING_MAP", "SAVING_MAP", "CHECKING_MAP", "MAP_READY");
        assertThat(steps(reportJobId)).containsExactly("COMPARING", "CHANGES_FOUND", "TRACING", "SCORING", "WRITING_REPORT", "DONE");
        JsonNode last = get("/api/v1/index-jobs/" + reportJobId + "/events?after=5");
        assertThat(last).hasSize(1);
        assertThat(last.at("/0/message").asText()).matches("Analysis ready: (Low|Medium|High|Critical) risk");
        assertThat(get("/api/v1/index-jobs/" + graphJob + "/events").at("/4/message").asText())
                .startsWith("Dependency map ready: ");

        JsonNode activity = get("/api/v1/repositories/" + repoId + "/activity?limit=3");
        assertThat(activity).hasSize(3);
        assertThat(activity.at("/0/at").asText()).isGreaterThanOrEqualTo(activity.at("/2/at").asText());
    }

    private List<String> steps(UUID jobId) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode e : get("/api/v1/index-jobs/" + jobId + "/events")) out.add(e.get("step").asText());
        return out;
    }

    @Test
    @Order(10)
    void aReportIsComputedByTheWorkerAndExposesItsFindings() throws Exception {
        ResponseEntity<String> res = post(reports(), body(0, 1));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode created = json.readTree(res.getBody());
        reportId = UUID.fromString(created.at("/report/id").asText());
        reportJobId = UUID.fromString(created.at("/job/id").asText());
        assertThat(created.at("/job/operation").asText()).isEqualTo("IMPACT");
        awaitJob(reportJobId.toString());

        JsonNode report = get("/api/v1/impact-reports/" + reportId);
        assertThat(report.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(report.get("algorithmVersion").asText()).isEqualTo(ImpactAnalyzer.ALGORITHM_VERSION);
        assertThat(report.get("baseCommit").asText()).isEqualTo(commits.get(0));
        assertThat(report.get("headCommit").asText()).isEqualTo(commits.get(1));
        assertThat(report.get("baseSnapshotId").asText()).isEqualTo(snapshots.get(commits.get(0)).toString());
        assertThat(report.get("headSnapshotId").asText()).isEqualTo(snapshots.get(commits.get(1)).toString());
        assertThat(report.get("changedCount").asInt()).isEqualTo(1);
        assertThat(report.get("affectedCount").asInt()).isEqualTo(6);
        assertThat(report.get("testsAffectedCount").asInt()).isEqualTo(2);
        assertThat(report.get("possibleCount").asInt()).isZero();
        assertThat(report.get("truncated").asBoolean()).isFalse();
        assertThat(report.get("riskFactors")).hasSize(7);
        assertThat(report.get("riskScore").asInt()).isBetween(0, 100);
        assertThat(report.get("riskLevel").asText()).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
        // the score is the sum of the factors, as reported
        int sum = 0;
        for (JsonNode f : report.get("riskFactors")) sum += f.get("points").asInt();
        assertThat(report.get("riskScore").asInt()).isEqualTo(Math.min(100, sum));

        JsonNode affected = get("/api/v1/impact-reports/" + reportId + "/findings?type=AFFECTED");
        assertThat(affected.get("total").asInt()).isEqualTo(6);
        JsonNode first = affected.get("items").get(0);
        assertThat(first.get("distance").asInt()).isEqualTo(1);
        assertThat(first.get("name").asText()).isEqualTo("shop.Pricing#total(double)");
        assertThat(first.get("changedSymbol").asText()).isEqualTo("shop.Pricing#tax(double)");
        assertThat(first.at("/chain/0/path").asText()).isNotBlank();
        assertThat(affected.get("items").get(5).get("test").asBoolean()).isTrue();
        JsonNode endpoint = get("/api/v1/impact-reports/" + reportId + "/findings?type=ENDPOINT");
        assertThat(endpoint.get("total").asInt()).isEqualTo(1);
        assertThat(endpoint.at("/items/0/name").asText()).isEqualTo("POST /checkout");
        assertThat(endpoint.at("/items/0/changedSymbol").asText()).isEqualTo("shopweb.CheckoutController#checkout(double)");
        assertThat(endpoint.at("/items/0/distance").asInt()).isEqualTo(4);
        assertThat(endpoint.at("/items/0/chain")).hasSize(4);

        assertThat(get("/api/v1/impact-reports/" + reportId + "/findings?type=CHANGED").get("items").get(0).get("changeKind").asText())
                .isEqualTo("MODIFIED");
        assertThat(get("/api/v1/impact-reports/" + reportId + "/findings?type=AFFECTED&module=web").get("total").asInt()).isEqualTo(3);
        assertThat(get("/api/v1/impact-reports/" + reportId + "/findings?type=AFFECTED&test=true").get("total").asInt()).isEqualTo(2);
        assertThat(get("/api/v1/impact-reports/" + reportId + "/findings?type=AFFECTED&test=false").get("total").asInt()).isEqualTo(4);
        JsonNode page = get("/api/v1/impact-reports/" + reportId + "/findings?type=AFFECTED&limit=2&offset=2");
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("total").asInt()).isEqualTo(6);
        assertThat(rest.getForEntity("/api/v1/impact-reports/" + reportId + "/findings?type=NOPE", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(reports()).get(0).get("id").asText()).isEqualTo(reportId.toString());
    }

    @Test
    @Order(11)
    void repeatingTheRequestReturnsTheSameReportAndJobWithoutNewWork() throws Exception {
        long jobsBefore = count("select count(*) from index_job where operation = 'IMPACT'");

        JsonNode again = json.readTree(post(reports(), body(0, 1)).getBody());

        assertThat(again.at("/report/id").asText()).isEqualTo(reportId.toString());
        assertThat(again.at("/job/id").asText()).isEqualTo(reportJobId.toString());
        assertThat(again.at("/report/status").asText()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from impact_report")).isEqualTo(1);
        assertThat(count("select count(*) from index_job where operation = 'IMPACT'")).isEqualTo(jobsBefore);
    }

    @Test
    @Order(12)
    void aDifferentDepthIsADifferentReport() throws Exception {
        JsonNode shallow = json.readTree(post(reports(), json.writeValueAsString(Map.of("baseCommit", commits.get(0),
                "headCommit", commits.get(1), "depth", 1))).getBody());
        awaitJob(shallow.at("/job/id").asText());

        assertThat(shallow.at("/report/id").asText()).isNotEqualTo(reportId.toString());
        assertThat(get("/api/v1/impact-reports/" + shallow.at("/report/id").asText()).get("affectedCount").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from impact_report")).isEqualTo(2);
    }

    @Test
    @Order(13)
    void aSecondScenarioReportShowsTheBreakingApiFactor() throws Exception {
        JsonNode created = json.readTree(post(reports(), body(1, 2)).getBody());
        awaitJob(created.at("/job/id").asText());

        JsonNode report = get("/api/v1/impact-reports/" + created.at("/report/id").asText());
        JsonNode breaking = null;
        for (JsonNode f : report.get("riskFactors")) if (f.get("signal").asText().equals("03-breaking-api")) breaking = f;
        assertThat(breaking).isNotNull();
        assertThat(breaking.get("points").asInt()).isEqualTo(20);
        assertThat(breaking.get("reason").asText()).contains("shop.Pricing#total(double)");
        JsonNode changed = get("/api/v1/impact-reports/" + created.at("/report/id").asText() + "/findings?type=CHANGED");
        assertThat(changed.get("total").asInt()).isEqualTo(2);
    }

    @Test
    @Order(14)
    void aCompletedReportIsImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("update impact_finding set name = 'tampered' where report_id = ?", reportId))
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("delete from impact_finding where report_id = ?", reportId))
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("insert into impact_finding (report_id, finding_type, ordinal, name, change_kind)"
                                + " values (?, 'CHANGED', 999, 'late', 'ADDED')", reportId))
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("update impact_report set risk_score = 0 where id = ?", reportId))
                .hasMessageContaining("immutable");
    }

    @Test
    @Order(15)
    void unknownReportsAre404AndUnfinishedOnesAreNotReadable() throws Exception {
        assertThat(rest.getForEntity("/api/v1/impact-reports/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/impact-reports/" + UUID.randomUUID() + "/findings", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/repositories/" + UUID.randomUUID() + "/impact-reports", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(15)
    void theMarkdownReportIsDownloadableAndNamesItsSnapshots() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/impact-reports/" + reportId + "/report.md", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("text/markdown");
        assertThat(res.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"impact-" + commits.get(0).substring(0, 7) + "-" + commits.get(1).substring(0, 7) + ".md\"");
        String md = res.getBody();
        assertThat(md).startsWith("# Change impact report")
                .contains("| Base graph snapshot | `" + snapshots.get(commits.get(0)) + "` |")
                .contains("| Head graph snapshot | `" + snapshots.get(commits.get(1)) + "` |")
                .contains("| Algorithm | `" + ImpactAnalyzer.ALGORITHM_VERSION + "` |")
                .contains("| MODIFIED | `shop.Pricing#tax(double)` |")
                .contains("### Tests to run (2)")
                .contains("`shopweb.CheckoutTest#paysTheTotal()`")
                .contains("`shop.PricingTest`");
        // the stored report renders identically every time
        assertThat(rest.getForEntity("/api/v1/impact-reports/" + reportId + "/report.md", String.class).getBody()).isEqualTo(md);
        assertThat(rest.getForEntity("/api/v1/impact-reports/" + UUID.randomUUID() + "/report.md", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(16)
    void deletingTheRepositoryRemovesItsReportsAndFindings() {
        assertThat(count("select count(*) from impact_finding")).isPositive();

        jdbc.update("delete from repository where id = ?", repoId);

        assertThat(count("select count(*) from impact_report")).isZero();
        assertThat(count("select count(*) from impact_finding")).isZero();
    }

    @Test
    @Order(15)
    void changingAnImplementationReachesCallersOfTheInterfaceMethodAsUncertain() throws Exception {
        prepare(commits.get(6));
        prepare(commits.get(7));

        ImpactAnalysis a = analyze(6, 7);

        assertThat(kinds(a.changed())).isEqualTo(
                "MODIFIED shop.CardFee#fee(double); MODIFIED shop.CardGateway#charge(double); MODIFIED shop.Discount3#apply(double)");
        // Discount#apply has six implementations: its caller Basket#total is not followed, and a note says so
        assertThat(a.notes()).singleElement().asString()
                .startsWith("Callers of shop.Discount#apply(double) were not followed")
                .contains("so do 5 other classes");
        // callers of the interface method are reached; callers of the concrete BaseFee#fee are not
        Set<String> labelled = Set.of("shop.Gateway#charge(double)", "shopweb.Payments#settle(double)");
        report("override-dispatch", names(a.affected()), labelled);
        AffectedSymbol caller = a.affected().stream().filter(x -> x.qualifiedName().equals("shopweb.Payments#settle(double)"))
                .findFirst().orElseThrow();
        assertThat(caller.certain()).isFalse();
        assertThat(caller.distance()).isEqualTo(2);
        assertThat(caller.chain()).extracting(h -> h.from() + " " + h.edgeType() + " " + h.to()).containsExactly(
                "shopweb.Payments#settle(double) CALLS shop.Gateway#charge(double)",
                "shop.Gateway#charge(double) OVERRIDDEN_BY shop.CardGateway#charge(double)");
    }
}
