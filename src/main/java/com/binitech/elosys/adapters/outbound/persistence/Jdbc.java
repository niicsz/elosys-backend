package com.binitech.elosys.adapters.outbound.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

final class Jdbc {
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

  private Jdbc() {}

  static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
  }

  static Long getLong(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }

  static Integer getInt(ResultSet rs, String column) throws SQLException {
    int v = rs.getInt(column);
    return rs.wasNull() ? null : v;
  }

  static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) ps.setNull(index, Types.BIGINT);
    else ps.setLong(index, value);
  }

  static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) ps.setNull(index, Types.INTEGER);
    else ps.setInt(index, value);
  }

  static void setString(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) ps.setNull(index, Types.VARCHAR);
    else ps.setString(index, value);
  }

  static String identifier(String name) {
    if (!IDENTIFIER.matcher(name).matches())
      throw new IllegalArgumentException("identificador inválido: " + name);
    return name;
  }
}
