package com.binitech.elosys.adapters.outbound.persistence.read;

import com.binitech.elosys.application.ports.outbound.ReadModelRefreshPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class MaterializedViewRefreshAdapter implements ReadModelRefreshPort {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(MaterializedViewRefreshAdapter.class);

  static final List<String> VIEWS =
      List.of(
          "mv_home_stats",
          "mv_supplier_year",
          "mv_asset_totals",
          "mv_asset_latest",
          "mv_asset_growth",
          "mv_category_expense",
          "mv_person_revenue",
          "mv_earmark_company_payments",
          "mv_category_ranking");

  private final JdbcClient jdbc;

  public MaterializedViewRefreshAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void refresh() {
    for (String view : VIEWS) {
      long start = System.nanoTime();
      jdbc.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view).update();
      LOGGER.info("  {} atualizada ({} ms)", view, (System.nanoTime() - start) / 1_000_000);
    }
  }
}
