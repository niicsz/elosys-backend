package com.binitech.elosys.adapters.outbound.persistence;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CopyWriter {
  private final JdbcTemplate template;
  private final TransactionTemplate tx;

  public CopyWriter(JdbcTemplate template, TransactionTemplate tx) {
    this.template = template;
    this.tx = tx;
  }

  public int insert(
      String table, List<String> columns, List<Object[]> rows, boolean ignoreConflicts) {
    if (rows.isEmpty()) return 0;
    String target = Jdbc.identifier(table);
    String cols = String.join(", ", columns.stream().map(Jdbc::identifier).toList());
    Integer inserted =
        tx.execute(
            status ->
                template.execute(
                    (ConnectionCallback<Integer>)
                        con -> {
                          try (Statement st = con.createStatement()) {
                            st.execute(
                                "CREATE TEMP TABLE IF NOT EXISTS copy_stage_"
                                    + target
                                    + " AS SELECT "
                                    + cols
                                    + " FROM "
                                    + target
                                    + " WITH NO DATA");
                            st.execute("TRUNCATE copy_stage_" + target);
                            st.execute(
                                "ALTER TABLE copy_stage_"
                                    + target
                                    + " ADD COLUMN IF NOT EXISTS copy_ord bigint"
                                    + " GENERATED ALWAYS AS IDENTITY");
                          }
                          copy(con, "copy_stage_" + target, cols, rows.iterator());
                          try (Statement st = con.createStatement()) {
                            int n =
                                st.executeUpdate(
                                    "INSERT INTO "
                                        + target
                                        + " ("
                                        + cols
                                        + ") SELECT "
                                        + cols
                                        + " FROM copy_stage_"
                                        + target
                                        + " ORDER BY copy_ord"
                                        + (ignoreConflicts ? " ON CONFLICT DO NOTHING" : ""));
                            st.execute("DROP TABLE copy_stage_" + target);
                            return n;
                          }
                        }));
    return inserted == null ? 0 : inserted;
  }

  public long copyDirect(
      Connection con, String table, List<String> columns, Iterator<Object[]> rows)
      throws SQLException {
    String cols = String.join(", ", columns.stream().map(Jdbc::identifier).toList());
    return copy(con, Jdbc.identifier(table), cols, rows);
  }

  private static long copy(Connection con, String table, String cols, Iterator<Object[]> rows)
      throws SQLException {
    CopyManager copy = con.unwrap(PGConnection.class).getCopyAPI();
    try (Reader reader = new CsvRowsReader(rows)) {
      return copy.copyIn("COPY " + table + " (" + cols + ") FROM STDIN WITH (FORMAT csv)", reader);
    } catch (IOException e) {
      throw new DataAccessResourceFailureException("COPY em " + table + " falhou", e);
    }
  }

  private static final class CsvRowsReader extends Reader {
    private final Iterator<Object[]> rows;
    private StringReader current = new StringReader("");

    CsvRowsReader(Iterator<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
      int n = current.read(buf, off, len);
      while (n == -1) {
        if (!rows.hasNext()) return -1;
        current = new StringReader(line(rows.next()));
        n = current.read(buf, off, len);
      }
      return n;
    }

    private static String line(Object[] row) {
      StringBuilder sb = new StringBuilder(row.length * 16);
      for (int i = 0; i < row.length; i++) {
        if (i > 0) sb.append(',');
        Object v = row[i];
        if (v == null) continue;
        String s =
            switch (v) {
              case Boolean b -> b ? "t" : "f";
              case OffsetDateTime t -> t.toString();
              default -> v.toString();
            };
        sb.append('"').append(s.replace("\u0000", "").replace("\"", "\"\"")).append('"');
      }
      return sb.append('\n').toString();
    }

    @Override
    public void close() {}
  }
}
