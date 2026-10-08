package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.inbound.JobRunView;
import com.binitech.elosys.application.ports.outbound.JobRunRepositoryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JobRunJdbcAdapter implements JobRunRepositoryPort {
  private static final String COLUMNS =
      "id, job, params::text AS params, status, started_at, finished_at, report::text AS report,"
          + " error";

  private final JdbcClient jdbc;

  public JobRunJdbcAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long start(String job, String paramsJson) {
    return jdbc.sql(
            "INSERT INTO job_run (job, params, status, started_at) VALUES (?, ?::jsonb, 'running', ?)"
                + " RETURNING id")
        .params(job, paramsJson, Jdbc.now())
        .query(Long.class)
        .single();
  }

  @Override
  public void succeed(long id, String reportJson) {
    jdbc.sql(
            "UPDATE job_run SET status = 'succeeded', finished_at = ?, report = ?::jsonb"
                + " WHERE id = ?")
        .params(Jdbc.now(), reportJson, id)
        .update();
  }

  @Override
  public void fail(long id, String error) {
    jdbc.sql("UPDATE job_run SET status = 'failed', finished_at = ?, error = ? WHERE id = ?")
        .params(Jdbc.now(), error, id)
        .update();
  }

  @Override
  public Optional<JobRunView> find(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM job_run WHERE id = ?")
        .param(id)
        .query(JobRunJdbcAdapter::map)
        .optional();
  }

  @Override
  public List<JobRunView> recent(int limit) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM job_run ORDER BY started_at DESC LIMIT ?")
        .param(limit)
        .query(JobRunJdbcAdapter::map)
        .list();
  }

  private static JobRunView map(ResultSet rs, int i) throws SQLException {
    OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
    OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
    return new JobRunView(
        rs.getLong("id"),
        rs.getString("job"),
        rs.getString("params"),
        rs.getString("status"),
        started == null ? null : started.toInstant(),
        finished == null ? null : finished.toInstant(),
        rs.getString("report"),
        rs.getString("error"));
  }
}
