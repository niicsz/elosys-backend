package com.binitech.elosys.application.usecases.rules;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort.ExpenseCandidate;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.SignalRepositoryPort;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.domain.signal.RuleRun;
import com.binitech.elosys.domain.signal.SignalActor;
import com.binitech.elosys.domain.signal.SignalDraft;
import com.binitech.elosys.domain.signal.SignalEvidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DisproportionateExpenseJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(DisproportionateExpenseJob.class);

  static final String RULE_NAME = "disproportionate_expense";
  static final String RULE_VERSION = "2.0";

  static final List<List<String>> CATEGORIES =
      List.of(
          List.of("CANETA"),
          List.of("LAPIS", "LÁPIS"),
          List.of("LAPISEIRA"),
          List.of("BORRACHA"),
          List.of("APONTADOR"),
          List.of("ADESIVO"),
          List.of("CRACHA", "CRACHÁ"),
          List.of("ETIQUETA"),
          List.of("CLIPS"),
          List.of("GRAMPO"),
          List.of("GRAMPEADOR"),
          List.of("REGUA", "RÉGUA"),
          List.of("BLOCO DE ANOTA"),
          List.of("ENVELOPE"),
          List.of("MARCADOR DE TEXTO"),
          List.of("PRANCHETA"),
          List.of("PERFURADOR"),
          List.of("ELASTICO", "ELÁSTICO"));

  static final int MIN_SAMPLE_FOR_STATS = 20;
  static final int MEDIAN_MULTIPLIER_MEDIUM = 15;
  static final int MEDIAN_MULTIPLIER_HIGH = 30;
  static final long MIN_FLOOR_CENTS = 100_000;
  static final long FALLBACK_MEDIUM_FLOOR_CENTS = 500_000;
  static final long FALLBACK_HIGH_FLOOR_CENTS = 5_000_000;

  private final DetectionDataPort data;
  private final SignalRepositoryPort signals;
  private final ProvenanceService provenance;
  private final JsonPort json;

  public DisproportionateExpenseJob(
      DetectionDataPort data,
      SignalRepositoryPort signals,
      ProvenanceService provenance,
      JsonPort json) {
    this.data = data;
    this.signals = signals;
    this.provenance = provenance;
    this.json = json;
  }

  @Override
  public String name() {
    return "rule-disproportionate-expense";
  }

  @Override
  public String description() {
    return "sinaliza despesas de item barato (caneta, adesivo...) com valor desproporcional";
  }

  static String categoryFor(String description) {
    if (description == null) return null;
    for (List<String> cat : CATEGORIES)
      for (String spelling : cat) if (description.contains(spelling)) return cat.getFirst();
    return null;
  }

  record Stats(int n, BigDecimal median, long mediumFloor, long highFloor) {}

  static Stats stats(List<Long> sortedAmounts) {
    int n = sortedAmounts.size();
    if (n < MIN_SAMPLE_FOR_STATS)
      return new Stats(n, null, FALLBACK_MEDIUM_FLOOR_CENTS, FALLBACK_HIGH_FLOOR_CENTS);
    BigDecimal median =
        n % 2 == 1
            ? BigDecimal.valueOf(sortedAmounts.get(n / 2))
            : BigDecimal.valueOf(sortedAmounts.get(n / 2 - 1))
                .add(BigDecimal.valueOf(sortedAmounts.get(n / 2)))
                .divide(BigDecimal.TWO);
    long medium =
        Math.max(
            MIN_FLOOR_CENTS,
            roundHalfEven(median.multiply(BigDecimal.valueOf(MEDIAN_MULTIPLIER_MEDIUM))));
    long high =
        Math.max(
            MIN_FLOOR_CENTS,
            roundHalfEven(median.multiply(BigDecimal.valueOf(MEDIAN_MULTIPLIER_HIGH))));
    return new Stats(n, median, medium, high);
  }

  private static long roundHalfEven(BigDecimal v) {
    return v.setScale(0, RoundingMode.HALF_EVEN).longValueExact();
  }

  static Map<String, Object> params() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("categories", CATEGORIES);
    p.put("min_sample_for_stats", MIN_SAMPLE_FOR_STATS);
    p.put("median_multiplier_medium", MEDIAN_MULTIPLIER_MEDIUM);
    p.put("median_multiplier_high", MEDIAN_MULTIPLIER_HIGH);
    p.put("min_floor_cents", MIN_FLOOR_CENTS);
    p.put("fallback_medium_floor_cents", FALLBACK_MEDIUM_FLOOR_CENTS);
    p.put("fallback_high_floor_cents", FALLBACK_HIGH_FLOOR_CENTS);
    return p;
  }

  @Override
  public Map<String, Object> run(JobParameters parameters) {
    signals.resetRule(RULE_NAME);
    List<String> keywords = CATEGORIES.stream().flatMap(List::stream).toList();
    List<ExpenseCandidate> rows = data.expensesMatchingAny(keywords);

    Map<String, List<ExpenseCandidate>> byCategory = new LinkedHashMap<>();
    for (ExpenseCandidate r : rows) {
      String cat = categoryFor(r.description());
      if (cat != null) byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
    }
    Map<String, Stats> stats = new LinkedHashMap<>();
    byCategory.forEach(
        (cat, list) -> {
          List<Long> amounts =
              list.stream()
                  .map(r -> r.amountCents() == null ? 0L : r.amountCents())
                  .sorted()
                  .toList();
          Stats s = stats(amounts);
          stats.put(cat, s);
          LOGGER.info(
              "  {} n={} mediana={} médio>={} alto>={}",
              cat,
              s.n(),
              s.median() == null ? "n/d (pisos fixos)" : brl(s.median()),
              brl(BigDecimal.valueOf(s.mediumFloor())),
              brl(BigDecimal.valueOf(s.highFloor())));
        });

    long runId =
        signals.startRun(
            new RuleRun(RULE_NAME, RULE_VERSION, provenance.gitCommit(), json.write(params())));
    List<SignalDraft> drafts = new ArrayList<>();
    for (ExpenseCandidate r : rows) {
      String cat = categoryFor(r.description());
      if (cat == null) continue;
      Stats s = stats.get(cat);
      long amount = r.amountCents() == null ? 0 : r.amountCents();
      if (amount < s.mediumFloor()) continue;
      String severity = amount >= s.highFloor() ? "high" : "medium";
      drafts.add(
          new SignalDraft(
              "cheap_item_high_value",
              severity,
              explanation(r, cat, s, amount),
              amount,
              null,
              actors(r),
              List.of(new SignalEvidence("campaign_expense", r.id()))));
    }
    signals.insertSignals(runId, drafts);
    signals.finishRun(runId, drafts.size());

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("rule_run_id", runId);
    report.put("signals", drafts.size());
    report.put("by_severity", signals.countBySeverity(runId));
    LOGGER.info("pronto: {}", report);
    return report;
  }

  static String explanation(ExpenseCandidate r, String cat, Stats s, long amount) {
    String category = cat.toLowerCase(Locale.ROOT);
    String basis;
    if (s.median() != null) {
      basis =
          s.median().signum() != 0
              ? String.format(
                  Locale.ROOT,
                  "%sx a mediana de R$ %s pra \"%s\" (%s despesas nessa categoria no histórico)",
                  new BigDecimal(amount / s.median().doubleValue())
                      .setScale(0, RoundingMode.HALF_EVEN)
                      .toPlainString(),
                  money(s.median()),
                  category,
                  String.format(Locale.ROOT, "%,d", s.n()))
              : "categoria sem mediana estável";
    } else {
      basis =
          "categoria \""
              + category
              + "\" com poucas despesas no histórico ("
              + s.n()
              + ") — piso fixo aplicado";
    }
    return "Despesa de campanha de R$ "
        + money(BigDecimal.valueOf(amount))
        + " descrita como \""
        + r.description()
        + "\" — "
        + basis
        + ". Pode ser lote com itens não detalhados na descrição, compra em grande volume, ou"
        + " erro de digitação no valor; não é, por si só, indício de irregularidade.";
  }

  private static List<SignalActor> actors(ExpenseCandidate r) {
    List<SignalActor> actors = new ArrayList<>();
    if (r.candidatePersonId() != null)
      actors.add(SignalActor.person(r.candidatePersonId(), "candidate"));
    if (r.supplierPersonId() != null)
      actors.add(SignalActor.person(r.supplierPersonId(), "supplier"));
    if (r.supplierCompanyId() != null)
      actors.add(SignalActor.company(r.supplierCompanyId(), "supplier"));
    return actors;
  }

  static String money(BigDecimal cents) {
    return String.format(Locale.ROOT, "%,.2f", cents.movePointLeft(2));
  }

  private static String brl(BigDecimal cents) {
    return "R$ " + money(cents);
  }
}
