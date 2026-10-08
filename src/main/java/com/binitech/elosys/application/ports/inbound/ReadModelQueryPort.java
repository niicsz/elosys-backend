package com.binitech.elosys.application.ports.inbound;

import java.util.List;
import java.util.Map;

public interface ReadModelQueryPort {
  Map<String, Object> search(String q);

  Map<String, Object> home(Integer year);

  Map<String, Object> meta();

  Map<String, Object> politician(long personId, Integer year);

  List<Map<String, Object>> politicianCategoryExpenses(
      long personId, String category, Integer year);

  Map<String, Object> entity(String cpfCnpj, Integer year);

  Map<String, Object> finance(FinanceRequest request);

  Map<String, Object> earmarkPayments(Integer page, String q, String order);

  List<Map<String, Object>> topSuppliers(Integer year);

  Map<String, Object> assetsRanking(String type, Integer year, Integer page);

  Map<String, Object> circularDonations(String severity, String sort, Integer page);

  Map<String, Object> aiReviews(String verdict, String rule, Integer page);

  Map<String, Object> supplierPartners(String filter, String q, Integer page);

  Map<String, Object> discourse(
      String category, boolean group, String severity, String handle, String q, Integer page);

  Map<String, Object> disproportionateExpenses(String category, Integer year, Integer page);

  Map<String, Object> graphSearch(String q);

  Map<String, Object> graphNode(String cpfCnpj);

  Map<String, Object> graphExpand(String cpfCnpj);

  Map<String, Object> graphPaths(String newId, List<String> existingIds);

  record FinanceRequest(
      String scope,
      String dir,
      String id,
      Integer page,
      String q,
      String sort,
      String order,
      Integer year,
      String dateFrom,
      String dateTo,
      Long amountMin,
      Long amountMax,
      boolean onlyPoliticianOwned) {}
}
