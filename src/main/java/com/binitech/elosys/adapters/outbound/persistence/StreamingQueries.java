package com.binitech.elosys.adapters.outbound.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

@Component
public class StreamingQueries {
  private static final int FETCH_SIZE = 20_000;

  private final DataSource dataSource;

  public StreamingQueries(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void forEach(String sql, RowHandler handler, Object... params) {
    try (Connection con = dataSource.getConnection()) {
      boolean autoCommit = con.getAutoCommit();
      con.setAutoCommit(false);
      con.setReadOnly(true);
      try (PreparedStatement ps = con.prepareStatement(sql)) {
        ps.setFetchSize(FETCH_SIZE);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) handler.accept(rs);
        }
        con.commit();
      } finally {
        con.setReadOnly(false);
        con.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("leitura em streaming falhou: " + sql, e);
    }
  }

  @FunctionalInterface
  public interface RowHandler {
    void accept(ResultSet rs) throws SQLException;
  }
}
