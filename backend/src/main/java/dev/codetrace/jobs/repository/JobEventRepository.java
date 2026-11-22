package dev.codetrace.jobs.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.codetrace.common.util.JsonCodec;
import dev.codetrace.common.util.Texts;
import dev.codetrace.jobs.model.JobEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The step-by-step progress of jobs. */
@Repository
public class JobEventRepository {
    private static final String SELECT = """
            select e.id, e.job_id, j.operation, j.commit_hash, e.attempt, e.seq, e.at, e.step, e.message, e.detail::text as detail
              from job_event e join index_job j on j.id = e.job_id""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public JobEventRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Appends an event unless the attempt already has {@code maxPerAttempt}; returns whether it was stored. */
    public boolean append(UUID jobId, int attempt, String step, String message, Map<String, ?> detail, int maxPerAttempt) {
        return jdbc.sql("""
                insert into job_event (job_id, attempt, seq, step, message, detail)
                select :j, :a, coalesce((select max(seq) from job_event where job_id = :j), 0) + 1, :s, :m, cast(:d as jsonb)
                 where (select count(*) from job_event where job_id = :j and attempt = :a) < :max""")
                .param("j", jobId).param("a", attempt).param("s", step).param("m", Texts.truncate(message, 200))
                .param("d", json.write(detail)).param("max", maxPerAttempt).update() == 1;
    }

    /** Events of one job after {@code afterSeq}, oldest first. */
    public List<JobEvent> forJob(UUID jobId, int afterSeq, int limit) {
        return jdbc.sql(SELECT + " where e.job_id = :j and e.seq > :after order by e.seq limit :lim")
                .param("j", jobId).param("after", afterSeq).param("lim", limit).query(this::map).list();
    }

    /** The newest events of a repository's jobs, newest first. */
    public List<JobEvent> forRepository(UUID repositoryId, int limit) {
        return jdbc.sql(SELECT + " where j.repository_id = :r order by e.at desc, e.id desc limit :lim")
                .param("r", repositoryId).param("lim", limit).query(this::map).list();
    }

    private JobEvent map(ResultSet rs, int i) throws SQLException {
        return new JobEvent(rs.getLong("id"), rs.getObject("job_id", UUID.class), rs.getString("operation"),
                rs.getString("commit_hash"), rs.getInt("attempt"), rs.getInt("seq"), rs.getObject("at", OffsetDateTime.class),
                rs.getString("step"), rs.getString("message"),
                json.read(rs.getString("detail"), new TypeReference<Map<String, Object>>() { }, Map.of()));
    }
}
