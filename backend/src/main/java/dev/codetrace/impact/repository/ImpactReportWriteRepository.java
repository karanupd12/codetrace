package dev.codetrace.impact.repository;

import dev.codetrace.common.util.SourcePaths;
import dev.codetrace.impact.model.ImpactAnalysis;
import dev.codetrace.common.util.Batches;
import dev.codetrace.common.util.JsonCodec;
import dev.codetrace.impact.model.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.model.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.model.ImpactAnalysis.PossibleDependent;
import dev.codetrace.impact.risk.RiskAssessor.RiskAssessment;
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
public class ImpactReportWriteRepository {
    private static final String INSERT = """
            insert into impact_finding (report_id, finding_type, ordinal, name, node_type, path, line, module, is_test,
                                        change_kind, direct_dependents, distance, certain, confidence, changed_symbol,
                                        node_id, chain)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))""";

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public ImpactReportWriteRepository(JdbcTemplate jdbc, JsonCodec json) {
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
                    SourcePaths.displayModule(s.path()), SourcePaths.isTestPath(s.path()), c.kind().name(),
                    c.directDependents(), null, null, null, "", s.nodeId(), null});
        }
        n = 0;
        for (AffectedSymbol x : a.affected()) {
            rows.add(new Object[] {reportId, "AFFECTED", n++, x.qualifiedName(), x.nodeType(), x.path(), x.line(),
                    x.module(), x.test(), null, null, x.distance(), x.certain(),
                    BigDecimal.valueOf(x.confidence()).setScale(3, RoundingMode.HALF_UP), x.changedSymbol(), x.nodeId(),
                    json.write(x.chain())});
        }
        n = 0;
        for (PossibleDependent p : a.possible()) {
            rows.add(new Object[] {reportId, "POSSIBLE", n++, p.qualifiedName(), "", p.path(), p.line(),
                    SourcePaths.displayModule(p.path()), SourcePaths.isTestPath(p.path()), null, null, null, null, null,
                    p.changedSymbol(), p.nodeId(), null});
        }
        for (List<Object[]> batch : Batches.partition(rows)) {
            jdbc.batchUpdate(INSERT, batch);
        }
        int done = jdbc.update("""
                update impact_report set status = 'COMPLETED', completed_at = now(), failure_reason = null,
                       risk_score = ?, risk_level = ?, risk_factors = cast(? as jsonb), changed_count = ?,
                       affected_count = ?, tests_affected_count = ?, possible_count = ?, unmapped_hunks = ?,
                       non_java_files = ?, truncated = ?
                 where id = ? and status in ('REQUESTED', 'RUNNING')""",
                risk.score(), risk.level().name(), json.write(risk.factors()), a.changed().size(), a.affected().size(),
                a.testsAffected(), a.possible().size(), a.unmappedHunks(), a.nonJavaFiles(), a.truncated(), reportId);
        if (done != 1) {
            throw new IllegalStateException("Report is not in a state that can be completed.");
        }
    }
}
