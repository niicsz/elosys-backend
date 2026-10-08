package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort;
import com.binitech.elosys.domain.review.SignalReview;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AiReviewJdbcAdapter implements AiReviewRepositoryPort {
  private static final String CIRCULAR_AMOUNT = "coalesce(s.amount_cents, 0)";

  private static final String EXPENSE_AMOUNT =
      "(SELECT ce.amount_cents FROM signal_evidence se JOIN campaign_expense ce ON ce.id ="
          + " se.record_id WHERE se.signal_id = s.id AND se.table_name = 'campaign_expense' LIMIT 1)";

  private final JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public AiReviewJdbcAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void deleteReviews(String model, String rule) {
    jdbc.sql(
            "DELETE FROM signal_ai_review WHERE model = ? AND signal_id IN (SELECT s.id FROM"
                + " signal s JOIN rule_run r ON r.id = s.rule_run_id WHERE r.rule = ?)")
        .params(model, rule)
        .update();
  }

  @Override
  public List<SignalToReview> signalsToReview(
      String rule, String model, int limit, boolean tightFirst, long minAmountCents) {
    String amount = rule.equals("circular_donations") ? CIRCULAR_AMOUNT : EXPENSE_AMOUNT;
    String order =
        tightFirst
            ? "coalesce(s.path_length, 99) ASC, " + amount + " DESC NULLS LAST"
            : amount + " DESC NULLS LAST";
    return jdbc.sql(
            "SELECT s.id, s.explanation FROM signal s JOIN rule_run r ON r.id = s.rule_run_id"
                + " WHERE r.rule = ? AND s.id NOT IN (SELECT signal_id FROM signal_ai_review WHERE"
                + " model = ?) AND "
                + amount
                + " >= ? ORDER BY "
                + order
                + ", s.id LIMIT ?")
        .params(rule, model, minAmountCents, limit)
        .query((rs, i) -> new SignalToReview(rs.getLong("id"), rs.getString("explanation")))
        .list();
  }

  @Override
  public List<ActorRef> actors(long signalId) {
    return jdbc.sql("SELECT type, actor_id FROM signal_actor WHERE signal_id = ?")
        .param(signalId)
        .query((rs, i) -> new ActorRef(rs.getString("type"), rs.getLong("actor_id")))
        .list();
  }

  @Override
  public String personName(long personId) {
    return jdbc.sql("SELECT canonical_name FROM people WHERE id = ?")
        .param(personId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Override
  public String companyName(long companyId) {
    return jdbc.sql(
            "SELECT coalesce(cr.legal_name, c.legal_name) FROM companies c"
                + " LEFT JOIN company_registry cr ON cr.company_id = c.id WHERE c.id = ?")
        .param(companyId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Override
  public Map<String, Object> candidacyBlurb(long personId) {
    return jdbc.sql(
            """
            SELECT latest.party_abbr, latest.office, latest.state, agg.years
            FROM (SELECT party_abbr, office, state FROM politician_history
                  WHERE person_id = ? ORDER BY year DESC, id DESC LIMIT 1) latest,
                 (SELECT string_agg(DISTINCT year::text, ',' ORDER BY year::text) AS years
                  FROM politician_history WHERE person_id = ?) agg
            """)
        .params(personId, personId)
        .query(
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("partido", rs.getString("party_abbr"));
              m.put("cargo", rs.getString("office"));
              m.put("uf", rs.getString("state"));
              m.put("anos", rs.getString("years"));
              return m;
            })
        .optional()
        .orElseGet(LinkedHashMap::new);
  }

  @Override
  public List<EvidenceRef> evidence(long signalId) {
    return jdbc.sql(
            "SELECT table_name, record_id FROM signal_evidence WHERE signal_id = ? ORDER BY"
                + " table_name, record_id")
        .param(signalId)
        .query((rs, i) -> new EvidenceRef(rs.getString("table_name"), rs.getLong("record_id")))
        .list();
  }

  @Override
  public LedgerFact donationFact(long donationId) {
    return jdbc.sql(
            """
            SELECT d.amount_cents, d.year, d.donor_name AS de, p.canonical_name AS para
            FROM campaign_donation d JOIN campaign_org co ON co.id = d.campaign_org_id
            JOIN people p ON p.id = co.person_id WHERE d.id = ?
            """)
        .param(donationId)
        .query(
            (rs, i) ->
                new LedgerFact(
                    Jdbc.getLong(rs, "amount_cents"),
                    Jdbc.getInt(rs, "year"),
                    rs.getString("de"),
                    rs.getString("para")))
        .optional()
        .orElse(null);
  }

  @Override
  public LedgerFact expenseFact(long expenseId) {
    return jdbc.sql(
            """
            SELECT ce.amount_cents, ce.year, p.canonical_name AS de, ce.supplier_name AS para
            FROM campaign_expense ce JOIN campaign_org co ON co.id = ce.campaign_org_id
            JOIN people p ON p.id = co.person_id WHERE ce.id = ?
            """)
        .param(expenseId)
        .query(
            (rs, i) ->
                new LedgerFact(
                    Jdbc.getLong(rs, "amount_cents"),
                    Jdbc.getInt(rs, "year"),
                    rs.getString("de"),
                    rs.getString("para")))
        .optional()
        .orElse(null);
  }

  @Override
  public Map<String, Object> disproportionateExpenseFacts(long signalId) {
    return jdbc.sql(
            """
            SELECT ce.description, ce.origin, ce.amount_cents, ce.year, ce.supplier_name,
                   ce.supplier_cpf_cnpj, co.person_id AS candidate_person_id,
                   p.canonical_name AS candidate_name
            FROM signal_evidence se JOIN campaign_expense ce ON ce.id = se.record_id
            LEFT JOIN campaign_org co ON co.id = ce.campaign_org_id
            LEFT JOIN people p ON p.id = co.person_id
            WHERE se.signal_id = ? AND se.table_name = 'campaign_expense' LIMIT 1
            """)
        .param(signalId)
        .query(
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("description", rs.getString("description"));
              m.put("origin", rs.getString("origin"));
              m.put("amount_cents", Jdbc.getLong(rs, "amount_cents"));
              m.put("year", Jdbc.getInt(rs, "year"));
              m.put("supplier_name", rs.getString("supplier_name"));
              m.put("supplier_cpf_cnpj", rs.getString("supplier_cpf_cnpj"));
              m.put("candidate_person_id", Jdbc.getLong(rs, "candidate_person_id"));
              m.put("candidate_name", rs.getString("candidate_name"));
              return m;
            })
        .optional()
        .orElse(null);
  }

  @Override
  public void upsertReview(long signalId, String model, SignalReview r, String prompt) {
    jdbc.sql(
            """
            INSERT INTO signal_ai_review (signal_id, model, reviewed_at, verdict, confidence,
                explanation, facts, prompt, raw_response, tokens_prompt, tokens_completion)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (signal_id, model) DO UPDATE SET
              reviewed_at = excluded.reviewed_at, verdict = excluded.verdict,
              confidence = excluded.confidence, explanation = excluded.explanation,
              facts = excluded.facts, prompt = excluded.prompt,
              raw_response = excluded.raw_response, tokens_prompt = excluded.tokens_prompt,
              tokens_completion = excluded.tokens_completion
            """)
        .params(
            signalId,
            model,
            Jdbc.now(),
            r.verdict(),
            r.confidence(),
            r.explanation(),
            json.writeValueAsString(r.facts()),
            prompt,
            r.rawResponse(),
            r.tokensPrompt(),
            r.tokensCompletion())
        .update();
  }
}
