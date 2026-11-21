package dev.codetrace.impact.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.codetrace.common.util.JsonCodec;
import dev.codetrace.impact.model.ImpactAnalysis.Hop;
import dev.codetrace.impact.model.Finding;
import dev.codetrace.common.web.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the findings of a completed report. */
@Repository
public class ImpactFindingRepository {
    private static final String COLS = """
            finding_type, ordinal, name, node_type, path, line, module, is_test, change_kind, direct_dependents, distance,
            certain, confidence, changed_symbol, chain::text as chain""";
    private static final String WHERE = " where report_id = :r and (cast(:type as text) is null or finding_type = :type)"
            + " and (cast(:module as text) is null or module = :module)";

    private final JdbcClient jdbc;
    private static final TypeReference<List<Hop>> HOPS = new TypeReference<>() {
    };

    private final JsonCodec json;

    public ImpactFindingRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Page<Finding> page(UUID reportId, String type, String module, int limit, int offset) {
        long total = jdbc.sql("select count(*) from impact_finding" + WHERE)
                .param("r", reportId).param("type", type).param("module", module).query(Long.class).single();
        List<Finding> items = jdbc.sql("select " + COLS + " from impact_finding" + WHERE
                + " order by finding_type, ordinal limit :lim offset :off")
                .param("r", reportId).param("type", type).param("module", module).param("lim", limit).param("off", offset)
                .query(this::map).list();
        return new Page<>(items, limit, offset, total);
    }

    /** Every finding of a report in report order; used to render the Markdown report. Bounded by the analysis caps. */
    public List<Finding> all(UUID reportId) {
        return jdbc.sql("select " + COLS + " from impact_finding where report_id = :r order by finding_type, ordinal")
                .param("r", reportId).query(this::map).list();
    }

    private Finding map(ResultSet rs, int i) throws SQLException {
        List<Hop> hops = json.read(rs.getString("chain"), HOPS, List.of());
        java.math.BigDecimal conf = rs.getBigDecimal("confidence");
        return new Finding(rs.getString("finding_type"), rs.getInt("ordinal"), rs.getString("name"),
                rs.getString("node_type"), rs.getString("path"), rs.getInt("line"), rs.getString("module"),
                rs.getBoolean("is_test"), rs.getString("change_kind"), (Integer) rs.getObject("direct_dependents"),
                (Integer) rs.getObject("distance"), (Boolean) rs.getObject("certain"),
                conf == null ? null : conf.doubleValue(), rs.getString("changed_symbol"), hops);
    }
}
