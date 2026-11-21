package dev.codetrace.impact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.impact.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.ImpactAnalysis.PossibleDependent;
import dev.codetrace.impact.RiskAssessor.RiskAssessment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores an analysis and completes its report in one transaction: either the report is COMPLETED with every finding
 * present, or nothing of this run is visible. A completed report is immutable (database triggers).
 */
@Component
public class ImpactReportWriter {
    private static final String INSERT = """
            insert into impact_finding (report_id, finding_type, ordinal, name, node_type, path, line, module, is_test,
                                        change_kind, direct_dependents, distance, certain, confidence, changed_symbol,
                                        node_id, chain)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ImpactReportWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void complete(UUID reportId, ImpactAnalysis a, RiskAssessment risk) {
        jdbc.update("delete from impact_finding where report_id = ?", reportId);
        List<Object[]> rows = new ArrayList<>();
        int n = 0;
        for (ChangedEntry c : a.changed()) {
            var s = c.any();
            rows.add(new Object[] {reportId, "CHANGED", n++, s.qualifiedName(), s.nodeType(), s.path(), s.startLine(),
                    ImpactAnalysis.moduleOf(s.path()), ImpactAnalysis.isTestPath(s.path()), c.kind().name(),
                    c.directDependents(), null, null, null, "", s.nodeId(), null});
        }
        n = 0;
        for (AffectedSymbol x : a.affected()) {
            rows.add(new Object[] {reportId, "AFFECTED", n++, x.qualifiedName(), x.nodeType(), x.path(), x.line(),
                    x.module(), x.test(), null, null, x.distance(), x.certain(),
                    BigDecimal.valueOf(x.confidence()).setScale(3, RoundingMode.HALF_UP), x.changedSymbol(), x.nodeId(),
                    toJson(x.chain())});
        }
        n = 0;
        for (PossibleDependent p : a.possible()) {
            rows.add(new Object[] {reportId, "POSSIBLE", n++, p.qualifiedName(), "", p.path(), p.line(),
                    ImpactAnalysis.moduleOf(p.path()), ImpactAnalysis.isTestPath(p.path()), null, null, null, null, null,
                    p.changedSymbol(), p.nodeId(), null});
        }
        for (int i = 0; i < rows.size(); i += 1000) {
            jdbc.batchUpdate(INSERT, rows.subList(i, Math.min(rows.size(), i + 1000)));
        }
        int done = jdbc.update("""
                update impact_report set status = 'COMPLETED', completed_at = now(), failure_reason = null,
                       risk_score = ?, risk_level = ?, risk_factors = cast(? as jsonb), changed_count = ?,
                       affected_count = ?, tests_affected_count = ?, possible_count = ?, unmapped_hunks = ?,
                       non_java_files = ?, truncated = ?
                 where id = ? and status in ('REQUESTED', 'RUNNING')""",
                risk.score(), risk.level().name(), toJson(risk.factors()), a.changed().size(), a.affected().size(),
                a.testsAffected(), a.possible().size(), a.unmappedHunks(), a.nonJavaFiles(), a.truncated(), reportId);
        if (done != 1) {
            throw new IllegalStateException("Report is not in a state that can be completed.");
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise report data.", e);
        }
    }
}
