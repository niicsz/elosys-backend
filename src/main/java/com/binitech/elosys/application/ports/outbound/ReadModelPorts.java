package com.binitech.elosys.application.ports.outbound;

import java.util.List;
import java.util.Map;

public final class ReadModelPorts {
  private ReadModelPorts() {}

  public interface PoliticianReadPort {
    List<Map<String, Object>> searchPeople(String query, int limit);

    Map<String, Object> header(long personId);

    String photoUrl(long personId);

    Map<String, Object> photoProvenance(long personId);

    List<Map<String, Object>> candidacies(long personId);

    List<Map<String, Object>> campaignOrgs(long personId);

    List<Map<String, Object>> socialMedia(long personId);

    Map<String, Object> assets(long personId);

    Map<String, Object> earmarks(long personId);

    Map<String, Object> finance(long personId, Integer year);

    Map<String, Object> signals(long personId);

    Map<String, Object> donationNetwork(long personId);

    List<Map<String, Object>> categoryExpenseDetail(long personId, String category, Integer year);
  }

  public interface EntityReadPort {
    Long candidatePersonId(String digits);

    Map<String, Object> entityProfile(String digits, Integer year);

    Map<String, Object> companyEarmarks(String cnpj);

    Map<String, Object> financePage(FinanceQuery query);

    Map<String, Object> earmarkPayments(int page, String q, boolean ascending);
  }

  public record FinanceQuery(
      String scope,
      String id,
      String dir,
      int page,
      String q,
      String sort,
      boolean ascending,
      Integer year,
      String dateFrom,
      String dateTo,
      Long amountMinCents,
      Long amountMaxCents,
      boolean onlyPoliticianOwned) {}

  public interface RankingReadPort {
    List<Long> expenseYears();

    List<Long> assetYears();

    Map<String, Object> assetsRanking(Integer year, boolean ascending, int limit, int offset);

    Map<String, Object> assetsGrowth(boolean ascending, int limit, int offset);

    List<Map<String, Object>> topSuppliers(Integer year, int limit);

    Map<String, Object> expenseCategoryRanking(
        String category, Integer year, int limit, int offset);

    List<String> expenseCategories();

    Map<String, Object> homeStats(Integer year);

    Map<String, Object> sidebarCounts();
  }

  public interface SignalReadPort {
    Map<String, Object> circularSummary();

    List<Map<String, Object>> circularSignals(String severity, String sort, int limit, int offset);

    Map<String, Object> aiReviewSummary();

    long aiReviewCount(String verdict, String rule);

    List<Map<String, Object>> aiReviews(String verdict, String rule, int limit, int offset);

    Map<String, Object> supplierPartnerSummary();

    long supplierPartnerCount(String filter, String q);

    List<Map<String, Object>> supplierPartners(String filter, String q, int limit, int offset);

    Map<String, Object> discourseSummary();

    long discourseCount(DiscourseFilter filter);

    List<Map<String, Object>> discourseSignals(DiscourseFilter filter, int limit, int offset);

    long disproportionateExpenseCount();
  }

  public record DiscourseFilter(
      String category, String severity, boolean group, String handle, String q, Long personId) {}

  public interface GraphReadPort {
    List<Map<String, Object>> searchEntities(String query, int limit);

    Map<String, Object> paths(String newId, List<String> existingIds);

    Map<String, Object> resolveNode(String id);

    Map<String, Object> nodeNetwork(String id, int limit);
  }
}
