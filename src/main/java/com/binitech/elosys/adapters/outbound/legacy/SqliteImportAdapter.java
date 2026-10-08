package com.binitech.elosys.adapters.outbound.legacy;

import com.binitech.elosys.application.ports.outbound.LegacyImportPort;
import com.binitech.elosys.domain.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SqliteImportAdapter implements LegacyImportPort {
  private static final Logger LOGGER = LoggerFactory.getLogger(SqliteImportAdapter.class);

  private static final List<String> TABLES =
      List.of(
          "source",
          "collection",
          "collection_file",
          "parse",
          "record_sources",
          "people",
          "rejected_cpf",
          "companies",
          "campaign_org",
          "campaign_donation",
          "campaign_expense",
          "campaign_expense_payment",
          "social_media",
          "politician_history",
          "rule_run",
          "signal",
          "signal_actor",
          "signal_evidence",
          "signal_ai_review",
          "sanction",
          "parliamentary_earmark",
          "parliamentary_earmark_beneficiary",
          "declared_assets",
          "candidate_photo",
          "company_registry",
          "company_partner",
          "candidate_supplier_partner",
          "social_account",
          "social_post",
          "social_post_review",
          "pessoa_fisica_search");

  private final DataSource dataSource;
  private final JsonMapper json = JsonMapper.builder().build();

  public SqliteImportAdapter(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Map<String, Object> importSqlite(Path sqliteFile, boolean truncateFirst) {
    if (!Files.isRegularFile(sqliteFile))
      throw new BusinessException("arquivo SQLite não encontrado: " + sqliteFile);
    Map<String, Object> report = new LinkedHashMap<>();
    Map<String, Long> rows = new LinkedHashMap<>();
    String url = "jdbc:sqlite:file:" + sqliteFile.toAbsolutePath().toString().replace('\\', '/');
    try (Connection sqlite = DriverManager.getConnection(url + "?mode=ro");
        Connection pg = dataSource.getConnection()) {
      Set<String> legacyTables = sqliteTables(sqlite);
      if (truncateFirst) truncateAll(pg);
      else ensureEmpty(pg);

      for (String table : TABLES) {
        if (!legacyTables.contains(table)) {
          LOGGER.info("  {}: não existe no SQLite, pulando", table);
          continue;
        }
        long n = importTable(sqlite, pg, table);
        rows.put(table, n);
      }
      for (String table : TABLES) {
        if (!table.equals("record_sources")
            && !table.equals("rejected_cpf")
            && !table.equals("signal_actor")
            && !table.equals("signal_evidence")
            && !table.equals("pessoa_fisica_search")) syncSequence(pg, table);
      }
      LOGGER.info("[>] VACUUM (FREEZE, ANALYZE)");
      try (Statement st = pg.createStatement()) {
        st.execute("VACUUM (FREEZE, ANALYZE)");
      }
    } catch (SQLException e) {
      throw new BusinessException("importação falhou: " + e.getMessage(), e);
    }
    report.put("source", sqliteFile.toAbsolutePath().toString());
    report.put("rows", rows);
    report.put("total_rows", rows.values().stream().mapToLong(Long::longValue).sum());
    return report;
  }

  private long importTable(Connection sqlite, Connection pg, String table) throws SQLException {
    Map<String, String> pgColumns = postgresColumns(pg, table);
    List<String> legacyColumns = sqliteColumns(sqlite, table);
    List<String> columns = new ArrayList<>();
    for (String c : legacyColumns) if (pgColumns.containsKey(c)) columns.add(c);
    if (columns.isEmpty()) return 0;
    List<String> types = columns.stream().map(pgColumns::get).toList();

    long start = System.nanoTime();
    LOGGER.info("[>] {} ({} colunas)", table, columns.size());
    List<String> indexes = dropSecondaryIndexes(pg, table);
    boolean autoCommit = pg.getAutoCommit();
    pg.setAutoCommit(false);
    long copied;
    try (Statement st = pg.createStatement()) {
      st.execute("SET LOCAL session_replication_role = replica");
      String select =
          "SELECT "
              + String.join(", ", columns.stream().map(c -> "\"" + c + "\"").toList())
              + " FROM \""
              + table
              + "\"";
      try (Statement legacy = sqlite.createStatement();
          ResultSet rs = legacy.executeQuery(select)) {
        CopyManager copy = pg.unwrap(PGConnection.class).getCopyAPI();
        copied =
            copy.copyIn(
                "COPY "
                    + table
                    + " ("
                    + String.join(", ", columns)
                    + ") FROM STDIN WITH (FORMAT csv)",
                new RowsReader(new ResultSetRows(rs, columns, types, table)));
      } catch (java.io.IOException e) {
        throw new SQLException("COPY em " + table + " falhou: " + e.getMessage(), e);
      }
      pg.commit();
    } catch (SQLException e) {
      pg.rollback();
      throw e;
    } finally {
      pg.setAutoCommit(autoCommit);
    }
    recreateIndexes(pg, table, indexes);
    LOGGER.info(
        "[ok] {}: {} linhas ({}s)",
        table,
        String.format(Locale.ROOT, "%,d", copied),
        (System.nanoTime() - start) / 1_000_000_000);
    return copied;
  }

  private Set<String> sqliteTables(Connection sqlite) throws SQLException {
    Set<String> out = new LinkedHashSet<>();
    try (Statement st = sqlite.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')")) {
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }

  private static List<String> sqliteColumns(Connection sqlite, String table) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = sqlite.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
      while (rs.next()) out.add(rs.getString("name"));
    }
    return out;
  }

  private static Map<String, String> postgresColumns(Connection pg, String table)
      throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    try (var ps =
        pg.prepareStatement(
            """
            SELECT column_name, data_type FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ? AND is_generated = 'NEVER'
            ORDER BY ordinal_position
            """)) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.put(rs.getString(1), rs.getString(2));
      }
    }
    return out;
  }

  private static List<String> dropSecondaryIndexes(Connection pg, String table)
      throws SQLException {
    List<String> definitions = new ArrayList<>();
    List<String> names = new ArrayList<>();
    try (var ps =
        pg.prepareStatement(
            """
            SELECT i.indexname, i.indexdef FROM pg_indexes i
            WHERE i.schemaname = 'public' AND i.tablename = ?
              AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conname = i.indexname)
            """)) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          names.add(rs.getString(1));
          definitions.add(rs.getString(2));
        }
      }
    }
    try (Statement st = pg.createStatement()) {
      for (String name : names) st.execute("DROP INDEX IF EXISTS \"" + name + "\"");
    }
    return definitions;
  }

  private static void recreateIndexes(Connection pg, String table, List<String> definitions)
      throws SQLException {
    if (definitions.isEmpty()) return;
    long start = System.nanoTime();
    try (Statement st = pg.createStatement()) {
      for (String def : definitions) st.execute(def);
    }
    LOGGER.info(
        "  {}: {} índices recriados ({}s)",
        table,
        definitions.size(),
        (System.nanoTime() - start) / 1_000_000_000);
  }

  private static void syncSequence(Connection pg, String table) throws SQLException {
    try (Statement st = pg.createStatement()) {
      st.execute(
          "SELECT setval(pg_get_serial_sequence('"
              + table
              + "', 'id'), coalesce((SELECT max(id) FROM "
              + table
              + "), 0) + 1, false)");
    }
  }

  private static void ensureEmpty(Connection pg) throws SQLException {
    try (Statement st = pg.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT (SELECT count(*) FROM source) + (SELECT count(*) FROM people)"
                    + " + (SELECT count(*) FROM companies)")) {
      rs.next();
      if (rs.getLong(1) > 0)
        throw new BusinessException(
            "o Postgres já tem dados; rode com --truncate para substituir tudo pelo SQLite");
    }
  }

  private static void truncateAll(Connection pg) throws SQLException {
    LOGGER.info("[>] TRUNCATE de todas as tabelas de dado");
    try (Statement st = pg.createStatement()) {
      st.execute("TRUNCATE " + String.join(", ", TABLES) + " RESTART IDENTITY CASCADE");
    }
  }

  private final class ResultSetRows implements Iterator<Object[]> {
    private final ResultSet rs;
    private final List<String> columns;
    private final List<String> types;
    private final String table;
    private long count;
    private Boolean hasNext;

    ResultSetRows(ResultSet rs, List<String> columns, List<String> types, String table) {
      this.rs = rs;
      this.columns = columns;
      this.types = types;
      this.table = table;
    }

    @Override
    public boolean hasNext() {
      if (hasNext == null) {
        try {
          hasNext = rs.next();
        } catch (SQLException e) {
          throw new IllegalStateException(e);
        }
      }
      return hasNext;
    }

    @Override
    public Object[] next() {
      if (!hasNext()) throw new NoSuchElementException();
      hasNext = null;
      Object[] row = new Object[columns.size()];
      try {
        for (int i = 0; i < row.length; i++)
          row[i] = convert(rs.getObject(i + 1), types.get(i), columns.get(i));
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
      if (++count % 1_000_000 == 0)
        LOGGER.info("  {}: {} linhas", table, String.format(Locale.ROOT, "%,d", count));
      return row;
    }

    private Object convert(Object value, String type, String column) {
      if (table.equals("record_sources") && column.equals("field") && value == null) return "";
      if (value == null) return null;
      return switch (type) {
        case "boolean" -> toBoolean(value);
        case "bigint", "integer", "smallint" -> toLong(value, column);
        case "jsonb" -> toJson(value);
        default -> value instanceof byte[] bytes ? new String(bytes) : value.toString();
      };
    }

    private Object toBoolean(Object value) {
      if (value instanceof Number n) return n.longValue() != 0;
      String s = value.toString().strip();
      return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("t");
    }

    private Object toLong(Object value, String column) {
      if (value instanceof Integer || value instanceof Long) return value;
      if (value instanceof Number n) {
        double d = n.doubleValue();
        if (d == Math.rint(d)) return (long) d;
        throw new IllegalStateException(
            table + "." + column + " tem valor fracionário " + d + " numa coluna inteira");
      }
      String s = value.toString().strip();
      if (s.isEmpty()) return null;
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        throw new IllegalStateException(table + "." + column + " não é inteiro: " + s, e);
      }
    }

    private Object toJson(Object value) {
      String s = value.toString();
      try {
        json.readTree(s);
        return s;
      } catch (JacksonException e) {
        return json.writeValueAsString(s);
      }
    }
  }

  private static final class RowsReader extends java.io.Reader {
    private final Iterator<Object[]> rows;
    private final StringBuilder buffer = new StringBuilder(1 << 16);
    private int position;

    RowsReader(Iterator<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    public int read(char[] out, int off, int len) {
      while (position >= buffer.length()) {
        buffer.setLength(0);
        position = 0;
        if (!rows.hasNext()) return -1;
        for (int k = 0; k < 256 && rows.hasNext(); k++) append(rows.next());
      }
      int n = Math.min(len, buffer.length() - position);
      buffer.getChars(position, position + n, out, off);
      position += n;
      return n;
    }

    private void append(Object[] row) {
      for (int i = 0; i < row.length; i++) {
        if (i > 0) buffer.append(',');
        Object v = row[i];
        if (v == null) continue;
        String s = v instanceof Boolean b ? (b ? "t" : "f") : v.toString();
        buffer.append('"');
        for (int c = 0; c < s.length(); c++) {
          char ch = s.charAt(c);
          if (ch == '\u0000') continue;
          if (ch == '"') buffer.append('"');
          buffer.append(ch);
        }
        buffer.append('"');
      }
      buffer.append('\n');
    }

    @Override
    public void close() {}
  }
}
