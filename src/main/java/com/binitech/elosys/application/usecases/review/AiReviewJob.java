package com.binitech.elosys.application.usecases.review;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort;
import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort.ActorRef;
import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort.EvidenceRef;
import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort.LedgerFact;
import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort.SignalToReview;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.SignalReviewerPort;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.LanguageModelUnavailableException;
import com.binitech.elosys.domain.review.SignalReview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiReviewJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(AiReviewJob.class);

  static final List<String> REVIEWABLE_RULES =
      List.of("circular_donations", "disproportionate_expense");
  static final int DEFAULT_LIMIT = 50;
  static final int MAX_CONSECUTIVE_ERRORS = 3;

  static final String SYSTEM_PROMPT =
      """
      Você analisa SINAIS DE ALERTA gerados por um sistema sobre dados PÚBLICOS de \
      financiamento de campanha eleitoral no Brasil (prestação de contas do TSE). \
      Os sinais NÃO são acusação — são indícios que precisam de checagem humana. \
      Seu trabalho é TRIAR: dizer se esse caso específico merece ou não a atenção \
      de um humano. É uma decisão de priorização, não um julgamento.

      Responda SEMPRE com um objeto JSON, e só ele:
      {
        "verdict": "bizarro" | "plausivel" | "inconclusivo",
        "confianca": "baixa" | "media" | "alta",
        "explicacao": "2 a 4 frases em português, objetivas, citando os fatos que pesaram",
        "fatos": ["ponto concreto 1", "ponto concreto 2", "..."]
      }

      - "plausivel": dá pra explicar por um padrão comum e legítimo — mesma \
      coligação/partido, conta nacional do partido repassando recursos aos próprios \
      candidatos, devolução de sobra, doação entre aliados do mesmo grupo, compra em \
      lote, preço dentro do razoável pro item.
      - "bizarro": tem pelo menos UM fato que dificulta a explicação simples e \
      justifica um humano olhar — por exemplo: ciclo curto e fechado entre pessoas \
      SEM partido/coligação em comum; empresa que doou milhões e recebeu de volta \
      quase o mesmo; fornecedor cujo ramo não tem nada a ver com o serviço; valor \
      de uma ordem de grandeza acima do resto das transações da cadeia; preço muito \
      acima de mercado sem lote que justifique. Não precisa de prova — precisa de um \
      fato concreto que você consiga nomear.
      - "inconclusivo": os fatos não apontam nem pra um lado nem pro outro.

      NUNCA afirme que houve crime, fraude ou irregularidade — "bizarro" quer dizer \
      "vale conferir", não "é culpado". Mas também NÃO se esconda no \
      "inconclusivo": se há um fato que chama atenção, diga "bizarro" e nomeie o \
      fato. Reserve "inconclusivo" pros casos em que realmente não dá pra dizer nada.""";

  private final AiReviewRepositoryPort reviews;
  private final SignalReviewerPort reviewer;
  private final JsonPort json;

  public AiReviewJob(AiReviewRepositoryPort reviews, SignalReviewerPort reviewer, JsonPort json) {
    this.reviews = reviews;
    this.reviewer = reviewer;
    this.json = json;
  }

  @Override
  public String name() {
    return "ai-review";
  }

  @Override
  public String description() {
    return "segunda opinião do Claude sobre os sinais: rotineiro vs. bizarro (requer"
        + " ANTHROPIC_API_KEY) [--limit=50] [--rule=...] [--order=amount|tight]"
        + " [--min-amount-brl=0] [--refresh]";
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    int limit = p.integer("limit", DEFAULT_LIMIT);
    List<String> rules = p.list("rule").isEmpty() ? REVIEWABLE_RULES : p.list("rule");
    String order = p.string("order", "amount");
    if (!order.equals("amount") && !order.equals("tight"))
      throw new BusinessException("--order deve ser amount ou tight");
    long minAmount = Math.round(p.decimal("min-amount-brl", 0) * 100);
    boolean refresh = p.flag("refresh");
    String model = reviewer.model();

    Map<String, Integer> byVerdict = new TreeMap<>();
    int reviewed = 0;
    int errors = 0;
    for (String rule : rules) {
      if (!REVIEWABLE_RULES.contains(rule)) {
        LOGGER.warn("regra desconhecida, pulando: {}", rule);
        continue;
      }
      if (refresh) reviews.deleteReviews(model, rule);
      List<SignalToReview> targets =
          reviews.signalsToReview(
              rule,
              model,
              limit,
              rule.equals("circular_donations") && order.equals("tight"),
              minAmount);
      LOGGER.info("  {}: {} sinais para revisar", rule, targets.size());
      Progress rc = new Progress(LOGGER, rule + " revisados", 10);
      int consecutive = 0;
      for (SignalToReview s : targets) {
        rc.tick();
        String prompt = userPrompt(rule, facts(rule, s));
        SignalReview review;
        try {
          review = reviewer.review(SYSTEM_PROMPT, prompt);
        } catch (LanguageModelUnavailableException e) {
          errors++;
          consecutive++;
          LOGGER.warn("sinal {}: {}", s.id(), e.getMessage());
          if (consecutive >= MAX_CONSECUTIVE_ERRORS) {
            LOGGER.error("3 erros seguidos — abortando (chave inválida? sem créditos?)");
            return summary(reviewed, byVerdict, errors, true);
          }
          continue;
        }
        consecutive = 0;
        reviews.upsertReview(s.id(), model, review, prompt);
        byVerdict.merge(review.verdict(), 1, Integer::sum);
        reviewed++;
      }
      rc.done();
    }
    return summary(reviewed, byVerdict, errors, false);
  }

  private static Map<String, Object> summary(
      int reviewed, Map<String, Integer> byVerdict, int errors, boolean aborted) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reviewed", reviewed);
    out.put("by_verdict", byVerdict);
    out.put("errors", errors);
    if (aborted) out.put("aborted", true);
    LOGGER.info("pronto: {}", out);
    return out;
  }

  private Map<String, Object> facts(String rule, SignalToReview s) {
    return rule.equals("circular_donations")
        ? circularFacts(s.id(), s.explanation())
        : disproportionateFacts(s.id(), s.explanation());
  }

  private Map<String, Object> circularFacts(long signalId, String explanation) {
    List<Map<String, Object>> entities = new ArrayList<>();
    for (ActorRef a : reviews.actors(signalId)) {
      if (a.type().equals("person")) {
        String name = reviews.personName(a.actorId());
        if (name == null) continue;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("nome", name);
        e.putAll(reviews.candidacyBlurb(a.actorId()));
        entities.add(e);
      } else {
        String name = reviews.companyName(a.actorId());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("nome", name);
        e.put("tipo", "empresa");
        entities.add(e);
      }
    }
    record Key(String from, String to, String kind) {}
    Map<Key, Map<String, Object>> transactions = new LinkedHashMap<>();
    for (EvidenceRef e : reviews.evidence(signalId)) {
      LedgerFact fact;
      String kind;
      if (e.tableName().equals("campaign_donation")) {
        fact = reviews.donationFact(e.recordId());
        kind = "doação";
      } else if (e.tableName().equals("campaign_expense")) {
        fact = reviews.expenseFact(e.recordId());
        kind = "despesa";
      } else continue;
      if (fact == null) continue;
      Map<String, Object> acc =
          transactions.computeIfAbsent(
              new Key(fact.from(), fact.to(), kind),
              k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("de", k.from());
                m.put("para", k.to());
                m.put("tipo", k.kind());
                m.put("valor_cents", 0L);
                m.put("qtd", 0);
                m.put("anos", new TreeSet<Integer>());
                return m;
              });
      acc.put(
          "valor_cents",
          (Long) acc.get("valor_cents") + (fact.amountCents() == null ? 0 : fact.amountCents()));
      acc.put("qtd", (Integer) acc.get("qtd") + 1);
      if (fact.year() != null) {
        @SuppressWarnings("unchecked")
        TreeSet<Integer> years = (TreeSet<Integer>) acc.get("anos");
        years.add(fact.year());
      }
    }
    List<Map<String, Object>> txs = new ArrayList<>();
    for (Map<String, Object> t : transactions.values()) {
      t.put("anos", new ArrayList<>((TreeSet<?>) t.get("anos")));
      txs.add(t);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("resumo", explanation);
    out.put("entidades", entities);
    out.put("transacoes", txs);
    return out;
  }

  private Map<String, Object> disproportionateFacts(long signalId, String explanation) {
    Map<String, Object> ev = reviews.disproportionateExpenseFacts(signalId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("resumo", explanation);
    if (ev == null) return out;
    out.put("descricao_item", ev.get("description"));
    out.put("categoria_tse", ev.get("origin"));
    out.put("valor_contratado_cents", ev.get("amount_cents"));
    out.put("ano", ev.get("year"));
    out.put("fornecedor", ev.get("supplier_name"));
    out.put("fornecedor_cnpj", ev.get("supplier_cpf_cnpj"));
    if (ev.get("candidate_person_id") instanceof Number personId) {
      Map<String, Object> candidate = new LinkedHashMap<>();
      candidate.put("nome", ev.get("candidate_name"));
      candidate.putAll(reviews.candidacyBlurb(personId.longValue()));
      out.put("candidato", candidate);
    }
    return out;
  }

  private String userPrompt(String rule, Map<String, Object> facts) {
    String kind =
        rule.equals("circular_donations")
            ? "DOAÇÃO CIRCULAR (loop de movimentação de campanha: o dinheiro sai de uma "
                + "campanha e, seguindo doações e despesas, volta pra mesma cadeia)"
            : "DESPESA DE CAMPANHA DESPROPORCIONAL (item tipicamente barato — caneta, "
                + "adesivo, crachá... — contratado por valor alto)";
    return "Tipo de sinal: "
        + kind
        + ".\n\nFatos (JSON):\n"
        + json.writePretty(facts)
        + "\n\nCom base SÓ nesses fatos, esse sinal é rotineiro/explicável ou genuinamente "
        + "estranho? Responda no formato JSON pedido.";
  }
}
