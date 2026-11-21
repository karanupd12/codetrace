package dev.codetrace.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.impact.ImpactAnalysis.Hop;
import dev.codetrace.impact.ImpactReportDao.Report;
import dev.codetrace.impact.ImpactService.Finding;
import dev.codetrace.impact.RiskSignal.RiskFactor;
import dev.codetrace.report.sections.AffectedSection;
import dev.codetrace.report.sections.ChangedSymbolsSection;
import dev.codetrace.report.sections.EvidenceSection;
import dev.codetrace.report.sections.FooterSection;
import dev.codetrace.report.sections.HeaderSection;
import dev.codetrace.report.sections.LimitationsSection;
import dev.codetrace.report.sections.PossibleDependentsSection;
import dev.codetrace.report.sections.SummarySection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarkdownReportRendererTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);

    private final MarkdownReportRenderer renderer = new MarkdownReportRenderer(List.of(new FooterSection(),
            new LimitationsSection(), new PossibleDependentsSection(), new EvidenceSection(), new AffectedSection(),
            new ChangedSymbolsSection(), new SummarySection(), new HeaderSection())); // deliberately unordered

    private static Report report(int score, String level, boolean truncated, int unmapped, int nonJava) {
        return new Report(ID, UUID.randomUUID(), BASE, HEAD, UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
                UUID.fromString("00000000-0000-0000-0000-0000000000b2"), "impact-1", 4, UUID.randomUUID(), "COMPLETED",
                score, level, "[]", 1, 2, 1, 0, unmapped, nonJava, truncated, null,
                OffsetDateTime.parse("2026-11-21T10:15:30Z"), OffsetDateTime.parse("2026-11-21T10:16:00+05:30"));
    }

    private static Finding changed(String name, String kind, int dependents) {
        return new Finding("CHANGED", 0, name, "METHOD", "core/src/main/java/p/A.java", 12, "core", false, kind, dependents,
                null, null, null, "", List.of());
    }

    private static Finding affected(String name, boolean test, int distance, boolean certain, List<Hop> chain) {
        return new Finding("AFFECTED", 0, name, "METHOD", test ? "web/src/test/java/p/ATest.java" : "web/src/main/java/p/B.java",
                7, test ? "web" : "web", test, null, null, distance, certain, certain ? 1.0 : 0.45, "p.A#a()", chain);
    }

    private static List<RiskFactor> factors() {
        return List.of(new RiskFactor("01-change-breadth", 0, 15, "1 production symbol(s) changed."),
                new RiskFactor("02-blast-radius", 5, 35, "2 production symbol(s) depend on the change."));
    }

    private ReportData data(Report r, List<Finding> changed, List<Finding> affected, List<Finding> possible) {
        return new ReportData(r, "shop", factors(), changed, affected, possible);
    }

    private static List<Hop> chain() {
        return List.of(new Hop("p.B#b()", "p.A#a()", "CALLS", "RESOLVED", 1.0, "web/src/main/java/p/B.java", 7));
    }

    @Test
    void rendersEverySectionInOrder() {
        String md = renderer.render(data(report(35, "MEDIUM", false, 0, 0), List.of(changed("p.A#a()", "MODIFIED", 1)),
                List.of(affected("p.B#b()", false, 1, true, chain()), affected("p.BTest#t()", true, 2, false, chain())),
                List.of()));

        assertThat(md).startsWith("# Change impact report\n");
        List<String> headings = md.lines().filter(l -> l.startsWith("## ")).toList();
        assertThat(headings).containsExactly("## Summary", "## Changed symbols (1)", "## Affected symbols", "## Evidence",
                "## Reading this report and its limits");
        assertThat(md).contains("**MEDIUM** risk (35/100)")
                .contains("| Base revision | `" + BASE + "` |")
                .contains("| Algorithm | `impact-1` |")
                .contains("| Completed (UTC) | `2026-11-21T04:46:00Z` |") // offset normalised to UTC
                .contains("| 02-blast-radius | 5 / 35 | 2 production symbol(s) depend on the change. |")
                .contains("| MODIFIED | `p.A#a()` | `core/src/main/java/p/A.java:12` | 1 |")
                .contains("| `p.B#b()` | `web` | 1 | proven | `p.A#a()` |")
                .contains("- `p.BTest#t()` in `web/src/test/java/p/ATest.java:7` (distance 2, uncertain)")
                .contains("CALLS, resolved, confidence 1.00 at web/src/main/java/p/B.java:7")
                .endsWith("Deterministic: the same snapshots and settings always produce the same report._\n");
    }

    @Test
    void sameDataAlwaysRendersTheSameDocument() {
        ReportData d = data(report(35, "MEDIUM", false, 0, 0), List.of(changed("p.A#a()", "MODIFIED", 1)),
                List.of(affected("p.B#b()", false, 1, true, chain())), List.of());

        assertThat(renderer.render(d)).isEqualTo(renderer.render(d));
    }

    @Test
    void anEmptyChangeStillProducesAUsefulReport() {
        String md = renderer.render(data(report(0, "LOW", false, 2, 0), List.of(), List.of(), List.of()));

        assertThat(md).contains("_No Java declaration changed._ 2 changed region(s) touch no declaration")
                .contains("_No production symbol depends on the changed code._")
                .contains("_No test in the dependency graph depends on the changed code._")
                .doesNotContain("## Evidence").doesNotContain("## Possible dependents");
    }

    @Test
    void possibleDependentsAreListedSeparatelyAndNeverAsAffected() {
        Finding p = new Finding("POSSIBLE", 0, "shopweb.Chain#viaChain()", "", "web/src/main/java/Chain.java", 9, "web", false,
                null, null, null, null, null, "shop.Order#price(double)", List.of());

        String md = renderer.render(data(report(30, "MEDIUM", false, 0, 0), List.of(), List.of(), List.of(p)));

        assertThat(md).contains("## Possible dependents (not proven) (1)")
                .contains("- `shopweb.Chain#viaChain()` at `web/src/main/java/Chain.java:9` may call `shop.Order#price(double)`");
    }

    @Test
    void limitsOfThisRunAreStated() {
        String md = renderer.render(data(report(60, "HIGH", true, 3, 4), List.of(), List.of(), List.of()));

        assertThat(md).contains("reached a configured limit").contains("3 changed region(s)").contains("4 non-Java or binary file(s)");
        assertThat(renderer.render(data(report(0, "LOW", false, 0, 0), List.of(), List.of(), List.of())))
                .doesNotContain("reached a configured limit").doesNotContain("non-Java or binary");
    }

    @Test
    void longTablesAreCappedWithAPointerToTheApi() {
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 130; i++) many.add(affected("p.B#m" + i + "()", false, 1, true, chain()));

        String md = renderer.render(data(report(60, "HIGH", false, 0, 0), List.of(), many, List.of()));

        assertThat(md).contains("### Production code (130)").contains("… and 30 more; the findings API lists all of them.");
        long tableRows = md.lines().filter(l -> l.startsWith("| `p.B#m") && l.contains("()` | `web` |")).count();
        assertThat(tableRows).isEqualTo(100); // capped, however many findings exist
    }

    @Test
    void untrustedTextCannotInjectMarkdownHtmlOrTableCells() {
        String evil = "evil`\n# Injected heading\n<script>alert(1)</script> | extra | cells [x](http://evil.example)";
        Finding c = changed(evil, "MODIFIED", 0);
        Finding a = new Finding("AFFECTED", 0, evil, "METHOD", "../" + evil, 3, evil, false, null, null, 1, true, 1.0, evil,
                List.of(new Hop(evil, evil, "CALLS", "RESOLVED", 1.0, evil, 1)));

        String md = renderer.render(data(report(10, "LOW", false, 0, 0), List.of(c), List.of(a), List.of()));

        assertThat(md.lines()).noneMatch(l -> l.startsWith("# Injected") || l.contains("\n# Injected"));
        assertThat(md.lines().filter(l -> l.startsWith("#")).toList())
                .allMatch(l -> l.startsWith("# Change impact") || l.startsWith("## ") || l.startsWith("### "));
        // every occurrence of the hostile text is inside a code span or the fenced evidence block, with backticks neutralised
        String withoutFences = md.replaceAll("(?s)```text.*?```", "");
        assertThat(md).doesNotContain("evil`");
        assertThat(withoutFences).doesNotContain("| extra"); // pipes are neutralised everywhere a table cell could break
        String outsideCode = md.replaceAll("(?s)```text.*?```", "").replaceAll("`[^`\n]*`", "");
        assertThat(outsideCode).doesNotContain("<script>").doesNotContain("evil.example").doesNotContain("Injected heading");
    }

    @Test
    void sectionsOutOfOrderAreSortedAndDuplicatesRejected() {
        ReportSection last = new ReportSection() {
            public int order() { return 2000; }
            public String id() { return "ai-explanation"; }
            public String render(ReportData d) { return "## Explanation\n\nadded without touching the renderer\n"; }
        };
        MarkdownReportRenderer extended = new MarkdownReportRenderer(List.of(last, new FooterSection(), new HeaderSection()));

        String md = extended.render(data(report(0, "LOW", false, 0, 0), List.of(), List.of(), List.of()));

        assertThat(md.indexOf("# Change impact report")).isLessThan(md.indexOf("Generated by CODETRACE"));
        assertThat(md.indexOf("Generated by CODETRACE")).isLessThan(md.indexOf("## Explanation"));
        assertThatThrownBy(() -> new MarkdownReportRenderer(List.of(new FooterSection(), new FooterSection())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("footer");
    }

    @Test
    void helpersNeutraliseAndCapUntrustedText() {
        assertThat(MarkdownText.code("a`b|c\nd")).isEqualTo("`a'b¦c d`");
        assertThat(MarkdownText.code("")).isEqualTo("—");
        assertThat(MarkdownText.code(null)).isEqualTo("—");
        assertThat(MarkdownText.code("x".repeat(500))).hasSize(202); // 199 chars + ellipsis + two backticks
        assertThat(MarkdownText.plain("a`b\nc")).isEqualTo("a'b c");
        assertThat(MarkdownText.cell("a|b\nc")).isEqualTo("a\\|b c");
        assertThat(MarkdownText.shortHash("0123456789")).isEqualTo("0123456");
    }
}
