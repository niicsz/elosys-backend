package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.inbound.ReadModelQueryPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.DiscourseFilter;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.EntityReadPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.FinanceQuery;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.GraphReadPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.PoliticianReadPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.RankingReadPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.SignalReadPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ReadModelUseCase implements ReadModelQueryPort {
  static final int SEARCH_LIMIT = 25;
  static final int GRAPH_SEARCH_LIMIT = 15;
  static final int GRAPH_NETWORK_LIMIT = 400;
  static final int DISCOURSE_SHOWN = 8;
  static final int RANKING_PAGE = 50;
  static final int CIRCULAR_PAGE = 50;
  static final int AI_REVIEW_PAGE = 30;
  static final int SUPPLIER_PARTNER_PAGE = 40;
  static final int DISCOURSE_PAGE = 25;
  static final int CATEGORY_PAGE = 40;
  static final int TOP_SUPPLIERS = 10;

  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Set<String> SEVERITIES = Set.of("high", "medium", "low");
  private static final Set<String> CIRCULAR_SORTS = Set.of("severity", "amount", "path_length");
  private static final Set<String> VERDICTS = Set.of("bizarro", "plausivel", "inconclusivo");
  private static final Set<String> RULES = Set.of("circular_donations", "disproportionate_expense");
  private static final Set<String> FINANCE_SORTS = Set.of("amount", "paid", "year", "date", "name");

  private final PoliticianReadPort politicians;
  private final EntityReadPort entities;
  private final RankingReadPort rankings;
  private final SignalReadPort signals;
  private final GraphReadPort graph;

  public ReadModelUseCase(
      PoliticianReadPort politicians,
      EntityReadPort entities,
      RankingReadPort rankings,
      SignalReadPort signals,
      GraphReadPort graph) {
    this.politicians = politicians;
    this.entities = entities;
    this.rankings = rankings;
    this.signals = signals;
    this.graph = graph;
  }

  private static boolean in(Set<String> allowed, String value) {
    return value != null && allowed.contains(value);
  }

  private static int page(Integer page) {
    return page == null || page < 1 ? 1 : page;
  }

  private static String digits(String v) {
    return v == null ? "" : v.replaceAll("\\D", "");
  }

  private Integer knownExpenseYear(Integer year) {
    return year != null && containsYear(rankings.expenseYears(), year) ? year : null;
  }

  private static boolean containsYear(List<?> years, int year) {
    for (Object y : years) if (y instanceof Number n && n.longValue() == year) return true;
    return false;
  }

  @Override
  public Map<String, Object> search(String q) {
    return Map.of("results", politicians.searchPeople(q == null ? "" : q, SEARCH_LIMIT));
  }

  @Override
  public Map<String, Object> home(Integer year) {
    Integer y = knownExpenseYear(year);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("year", y);
    out.put("years", rankings.expenseYears());
    out.put("stats", rankings.homeStats(y));
    out.put("topSuppliers", rankings.topSuppliers(y, TOP_SUPPLIERS));
    return out;
  }

  @Override
  public Map<String, Object> meta() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("expenseYears", rankings.expenseYears());
    out.put("assetYears", rankings.assetYears());
    out.put("expenseCategories", rankings.expenseCategories());
    out.put("sidebarCounts", rankings.sidebarCounts());
    return out;
  }

  @Override
  public Map<String, Object> politician(long personId, Integer year) {
    Map<String, Object> header = politicians.header(personId);
    if (header == null) return null;
    Integer y = knownExpenseYear(year);
    String photoUrl = politicians.photoUrl(personId);
    DiscourseFilter discourse = new DiscourseFilter(null, null, false, null, null, personId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("year", y);
    out.put("years", rankings.expenseYears());
    out.put("header", header);
    out.put("photoUrl", photoUrl);
    out.put("photoProvenance", photoUrl == null ? null : politicians.photoProvenance(personId));
    out.put("finance", politicians.finance(personId, y));
    out.put("candidacies", politicians.candidacies(personId));
    out.put("campaignOrgs", politicians.campaignOrgs(personId));
    out.put("socialMedia", politicians.socialMedia(personId));
    out.put("assets", politicians.assets(personId));
    out.put("earmarks", politicians.earmarks(personId));
    out.put("signals", politicians.signals(personId));
    out.put("donationNetwork", politicians.donationNetwork(personId));
    out.put("discourseCount", signals.discourseCount(discourse));
    out.put("discourse", signals.discourseSignals(discourse, DISCOURSE_SHOWN, 0));
    return out;
  }

  @Override
  public List<Map<String, Object>> politicianCategoryExpenses(
      long personId, String category, Integer year) {
    String c = category == null || category.equals("TODAS") ? null : category;
    if (c != null && !rankings.expenseCategories().contains(c)) c = null;
    return politicians.categoryExpenseDetail(personId, c, year);
  }

  @Override
  public Map<String, Object> entity(String cpfCnpj, Integer year) {
    String d = digits(cpfCnpj);
    if (d.length() != 11 && d.length() != 14) return null;
    Long personId = entities.candidatePersonId(d);
    if (personId != null) return Map.of("redirectPersonId", personId);
    Integer y = knownExpenseYear(year);
    Map<String, Object> profile = entities.entityProfile(d, y);
    if (profile == null) return null;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("year", y);
    out.put("years", rankings.expenseYears());
    out.put("profile", profile);
    out.put("companyEarmarks", d.length() == 14 ? entities.companyEarmarks(d) : null);
    return out;
  }

  @Override
  public Map<String, Object> finance(FinanceRequest r) {
    Map<String, Object> empty = new LinkedHashMap<>();
    empty.put("rows", List.of());
    empty.put("total", 0);
    empty.put("pageSize", 25);
    if (r.scope() == null || r.id() == null || r.id().isBlank()) return empty;
    boolean candidate = r.scope().equals("candidate");
    boolean entity = r.scope().equals("entity");
    if (!candidate && !entity) return empty;
    boolean validDir =
        candidate
            ? "received".equals(r.dir()) || "spent".equals(r.dir())
            : "given".equals(r.dir()) || "received".equals(r.dir());
    if (!validDir) return empty;
    if (candidate && !r.id().matches("\\d+")) return empty;
    return entities.financePage(
        new FinanceQuery(
            r.scope(),
            r.id(),
            r.dir(),
            page(r.page()),
            r.q() == null ? "" : r.q(),
            in(FINANCE_SORTS, r.sort()) ? r.sort() : "amount",
            "asc".equals(r.order()),
            r.year(),
            r.dateFrom() != null && DATE.matcher(r.dateFrom()).matches() ? r.dateFrom() : null,
            r.dateTo() != null && DATE.matcher(r.dateTo()).matches() ? r.dateTo() : null,
            r.amountMin(),
            r.amountMax(),
            r.onlyPoliticianOwned()));
  }

  @Override
  public Map<String, Object> earmarkPayments(Integer page, String q, String order) {
    return entities.earmarkPayments(page(page), q == null ? "" : q, "asc".equals(order));
  }

  @Override
  public List<Map<String, Object>> topSuppliers(Integer year) {
    return rankings.topSuppliers(year, TOP_SUPPLIERS);
  }

  @Override
  public Map<String, Object> assetsRanking(String type, Integer year, Integer page) {
    int offset = (page(page) - 1) * RANKING_PAGE;
    boolean growth = "crescimento".equals(type);
    Integer y = year != null && containsYear(rankings.assetYears(), year) ? year : null;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", growth ? "crescimento" : "bens");
    out.put("year", growth ? null : y);
    out.put("years", rankings.assetYears());
    out.put("pageSize", RANKING_PAGE);
    out.putAll(
        growth
            ? rankings.assetsGrowth(false, RANKING_PAGE, offset)
            : rankings.assetsRanking(y, false, RANKING_PAGE, offset));
    return out;
  }

  @Override
  public Map<String, Object> circularDonations(String severity, String sort, Integer page) {
    String sev = in(SEVERITIES, severity) ? severity : null;
    String s = in(CIRCULAR_SORTS, sort) ? sort : "severity";
    Map<String, Object> summary = signals.circularSummary();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", summary);
    out.put("pageSize", CIRCULAR_PAGE);
    out.put(
        "rows", signals.circularSignals(sev, s, CIRCULAR_PAGE, (page(page) - 1) * CIRCULAR_PAGE));
    return out;
  }

  @Override
  public Map<String, Object> aiReviews(String verdict, String rule, Integer page) {
    String v = in(VERDICTS, verdict) ? verdict : null;
    String r = in(RULES, rule) ? rule : null;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", signals.aiReviewSummary());
    out.put("total", signals.aiReviewCount(v, r));
    out.put("pageSize", AI_REVIEW_PAGE);
    out.put("rows", signals.aiReviews(v, r, AI_REVIEW_PAGE, (page(page) - 1) * AI_REVIEW_PAGE));
    return out;
  }

  @Override
  public Map<String, Object> supplierPartners(String filter, String q, Integer page) {
    String f = "self".equals(filter) || "others".equals(filter) ? filter : "all";
    String query = q == null ? "" : q.strip();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", signals.supplierPartnerSummary());
    out.put("total", signals.supplierPartnerCount(f, query));
    out.put("pageSize", SUPPLIER_PARTNER_PAGE);
    out.put(
        "rows",
        signals.supplierPartners(
            f, query, SUPPLIER_PARTNER_PAGE, (page(page) - 1) * SUPPLIER_PARTNER_PAGE));
    return out;
  }

  @Override
  public Map<String, Object> discourse(
      String category, boolean group, String severity, String handle, String q, Integer page) {
    String cat = category == null || category.isBlank() ? null : category;
    DiscourseFilter filter =
        new DiscourseFilter(
            cat,
            in(SEVERITIES, severity) ? severity : null,
            group && cat == null,
            handle,
            q == null || q.isBlank() ? null : q.strip(),
            null);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", signals.discourseSummary());
    out.put("total", signals.discourseCount(filter));
    out.put("pageSize", DISCOURSE_PAGE);
    out.put(
        "rows",
        signals.discourseSignals(filter, DISCOURSE_PAGE, (page(page) - 1) * DISCOURSE_PAGE));
    return out;
  }

  @Override
  public Map<String, Object> disproportionateExpenses(String category, Integer year, Integer page) {
    List<String> categories = rankings.expenseCategories();
    String c = category != null && categories.contains(category) ? category : null;
    Integer y = knownExpenseYear(year);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("categories", categories);
    out.put("category", c);
    out.put("years", rankings.expenseYears());
    out.put("year", y);
    out.put("signalsCount", signals.disproportionateExpenseCount());
    out.put("pageSize", CATEGORY_PAGE);
    out.putAll(
        rankings.expenseCategoryRanking(c, y, CATEGORY_PAGE, (page(page) - 1) * CATEGORY_PAGE));
    return out;
  }

  @Override
  public Map<String, Object> graphSearch(String q) {
    return Map.of("results", graph.searchEntities(q == null ? "" : q, GRAPH_SEARCH_LIMIT));
  }

  @Override
  public Map<String, Object> graphNode(String cpfCnpj) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("node", graph.resolveNode(cpfCnpj == null ? "" : cpfCnpj));
    return out;
  }

  @Override
  public Map<String, Object> graphExpand(String cpfCnpj) {
    return graph.nodeNetwork(cpfCnpj == null ? "" : cpfCnpj, GRAPH_NETWORK_LIMIT);
  }

  @Override
  public Map<String, Object> graphPaths(String newId, List<String> existingIds) {
    return graph.paths(newId == null ? "" : newId, existingIds == null ? List.of() : existingIds);
  }
}
