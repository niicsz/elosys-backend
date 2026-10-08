package com.binitech.elosys.application.usecases.rules;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.SignalRepositoryPort;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.domain.graph.CycleFinder;
import com.binitech.elosys.domain.graph.MoneyGraph;
import com.binitech.elosys.domain.signal.RuleRun;
import com.binitech.elosys.domain.signal.SignalActor;
import com.binitech.elosys.domain.signal.SignalDraft;
import com.binitech.elosys.domain.signal.SignalEvidence;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CircularDonationsJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(CircularDonationsJob.class);

  static final String RULE_NAME = "circular_donations";
  static final String RULE_VERSION = "1.1";
  static final int DEFAULT_MAX_DEPTH = 5;
  static final int DEFAULT_MAX_FANOUT = 400;
  static final long DEFAULT_MIN_AMOUNT_CENTS = 1_000_000;
  static final int HIGH_SEVERITY_MAX_LEN = 3;
  static final int EVIDENCE_ROWS_PER_EDGE = 10;
  private static final int WRITE_BATCH = 2_000;

  private final DetectionDataPort data;
  private final SignalRepositoryPort signals;
  private final ProvenanceService provenance;
  private final JsonPort json;

  public CircularDonationsJob(
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
    return "rule-circular-donations";
  }

  @Override
  public String description() {
    return "encontra ciclos de doações/despesas no banco inteiro (Tarjan SCC + DFS limitada)"
        + " [--max-depth=5] [--max-fanout=400] [--min-amount-brl=10000]";
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    int maxDepth = p.integer("max-depth", DEFAULT_MAX_DEPTH);
    int maxFanout = p.integer("max-fanout", DEFAULT_MAX_FANOUT);
    long minAmount =
        Math.round(p.decimal("min-amount-brl", DEFAULT_MIN_AMOUNT_CENTS / 100.0) * 100);
    signals.resetRule(RULE_NAME);

    MoneyGraph graph = buildGraph();
    CycleFinder.Result found = CycleFinder.find(graph, maxDepth, maxFanout);
    LOGGER.info(
        "  {} SCCs, {} com mais de um nó; {} ciclos ({} ramos por hubs pulados)",
        found.totalSccs(),
        found.nontrivialSccs(),
        found.cycles().size(),
        found.hubSkipped());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("max_depth", maxDepth);
    params.put("max_fanout", maxFanout);
    params.put("min_amount_cents", minAmount);
    long runId =
        signals.startRun(
            new RuleRun(RULE_NAME, RULE_VERSION, provenance.gitCommit(), json.write(params)));

    Lookups lookups = new Lookups();
    List<SignalDraft> batch = new ArrayList<>(WRITE_BATCH);
    long generated = 0;
    long belowFloor = 0;
    Progress rc = new Progress(LOGGER, "ciclos avaliados", 20_000);
    for (int[] cycle : found.cycles()) {
      rc.tick();
      Optional<SignalDraft> draft = toSignal(graph, cycle, minAmount, lookups);
      if (draft.isEmpty()) {
        belowFloor++;
        continue;
      }
      batch.add(draft.get());
      generated++;
      if (batch.size() >= WRITE_BATCH) {
        signals.insertSignals(runId, List.copyOf(batch));
        batch.clear();
      }
    }
    signals.insertSignals(runId, batch);
    rc.done();
    signals.finishRun(runId, generated);
    LOGGER.info("  {} ciclos abaixo do piso de {}", belowFloor, Progress.brl(minAmount));

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("rule_run_id", runId);
    report.put("nodes", graph.nodeCount());
    report.put("edges", graph.edgeCount());
    report.put("sccs_nontrivial", found.nontrivialSccs());
    report.put("cycles_found", found.cycles().size());
    report.put("hub_branches_skipped", found.hubSkipped());
    report.put("signals", generated);
    report.put("by_severity", signals.countBySeverity(runId));
    report.put("params", params);
    return report;
  }

  private MoneyGraph buildGraph() {
    Map<Long, String> orgCpf = data.campaignOrgCpfs();
    LOGGER.info("  {} candidaturas resolvidas para um CPF", orgCpf.size());
    MoneyGraph graph = new MoneyGraph();
    Progress donations = new Progress(LOGGER, "doações lidas", 1_000_000);
    data.forEachDonationEdge(
        e -> {
          donations.tick();
          String cpf = orgCpf.get(e.campaignOrgId());
          if (cpf != null)
            graph.addEdge(e.counterpart(), cpf, MoneyGraph.DONATION, e.amountCents());
        });
    donations.done();
    Progress expenses = new Progress(LOGGER, "despesas lidas", 1_000_000);
    data.forEachExpenseEdge(
        e -> {
          expenses.tick();
          String cpf = orgCpf.get(e.campaignOrgId());
          if (cpf != null) graph.addEdge(cpf, e.counterpart(), MoneyGraph.PAYMENT, e.amountCents());
        });
    expenses.done();
    graph.freeze();
    LOGGER.info("  grafo: {} nós, {} arestas", graph.nodeCount(), graph.edgeCount());
    return graph;
  }

  private final class Lookups {
    final Map<String, Optional<String>> names = new HashMap<>();
    final Map<String, Optional<SignalActor>> actors = new HashMap<>();
    final Map<String, List<SignalEvidence>> evidence = new HashMap<>();

    String name(String doc) {
      return names
          .computeIfAbsent(
              doc,
              d ->
                  Optional.ofNullable(
                      d.length() == 11 ? data.personNameByCpf(d) : data.companyNameByCnpj(d)))
          .orElse(null);
    }

    Optional<SignalActor> actor(String doc) {
      return actors.computeIfAbsent(
          doc,
          d -> {
            if (d.length() == 11)
              return Optional.ofNullable(data.personIdByCpf(d))
                  .map(id -> SignalActor.person(id, "cycle_member"));
            return Optional.ofNullable(data.companyIdByCnpj(d))
                .map(id -> SignalActor.company(id, "cycle_member"));
          });
    }

    List<SignalEvidence> evidence(String src, String dst, byte kind) {
      return evidence.computeIfAbsent(
          src + ">" + dst + ">" + kind,
          k -> {
            List<SignalEvidence> rows = new ArrayList<>();
            if (kind == MoneyGraph.DONATION || kind == MoneyGraph.BOTH)
              data.donationIdsBetween(src, dst, EVIDENCE_ROWS_PER_EDGE)
                  .forEach(id -> rows.add(new SignalEvidence("campaign_donation", id)));
            if (kind == MoneyGraph.PAYMENT || kind == MoneyGraph.BOTH)
              data.expenseIdsBetween(src, dst, EVIDENCE_ROWS_PER_EDGE)
                  .forEach(id -> rows.add(new SignalEvidence("campaign_expense", id)));
            return rows;
          });
    }
  }

  private Optional<SignalDraft> toSignal(
      MoneyGraph g, int[] cycle, long minAmount, Lookups lookups) {
    int n = cycle.length;
    long total = 0;
    for (int i = 0; i < n; i++) total += g.amount(cycle[i], cycle[(i + 1) % n]);
    if (total < minAmount) return Optional.empty();

    List<String> docs = new ArrayList<>(n);
    for (int id : cycle) docs.add(g.node(id));
    StringBuilder chain = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) chain.append(" -> ");
      String name = lookups.name(docs.get(i));
      chain.append(docs.get(i)).append(" (").append(name == null ? "sem nome" : name).append(")");
    }
    String explanation =
        "Loop de movimentação de campanha entre "
            + n
            + " entidades, R$ "
            + DisproportionateExpenseJob.money(BigDecimal.valueOf(total))
            + " movimentados no total: "
            + chain
            + " -> "
            + docs.getFirst()
            + ". Cada seta é uma doação recebida ou uma despesa paga por uma campanha à seguinte"
            + " da cadeia, fechando um ciclo. Pode ser coincidência entre campanhas de uma mesma"
            + " coligação, um ressarcimento, ou merecer uma checagem manual mais de perto — não é,"
            + " por si só, indício de irregularidade.";

    LinkedHashSet<SignalActor> actors = new LinkedHashSet<>();
    for (String doc : docs) lookups.actor(doc).ifPresent(actors::add);
    LinkedHashSet<SignalEvidence> evidence = new LinkedHashSet<>();
    for (int i = 0; i < n; i++) {
      byte kind = g.kind(cycle[i], cycle[(i + 1) % n]);
      if (kind == 0) continue;
      evidence.addAll(lookups.evidence(docs.get(i), docs.get((i + 1) % n), kind));
    }
    return Optional.of(
        new SignalDraft(
            "circular_donation",
            n <= HIGH_SEVERITY_MAX_LEN ? "high" : "medium",
            explanation,
            total,
            n,
            List.copyOf(actors),
            List.copyOf(evidence)));
  }
}
