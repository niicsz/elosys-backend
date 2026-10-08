package com.binitech.elosys.adapters.outbound.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class IdentitySequences {
  private final JdbcClient jdbc;

  public IdentitySequences(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long[] reserve(String table, int count) {
    String sequence =
        jdbc.sql("SELECT pg_get_serial_sequence(?, 'id')")
            .param(Jdbc.identifier(table))
            .query(String.class)
            .single();
    return jdbc
        .sql("SELECT nextval(?::regclass) FROM generate_series(1, ?) ORDER BY 1")
        .params(sequence, count)
        .query(Long.class)
        .list()
        .stream()
        .mapToLong(Long::longValue)
        .toArray();
  }

  public void syncToMax(String table) {
    String t = Jdbc.identifier(table);
    jdbc.sql(
            "SELECT setval(pg_get_serial_sequence('"
                + t
                + "', 'id'), coalesce((SELECT max(id) FROM "
                + t
                + "), 0) + 1, false)")
        .query(Long.class)
        .single();
  }
}
