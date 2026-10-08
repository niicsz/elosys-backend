package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.SignalRepositoryPort;
import com.binitech.elosys.domain.signal.RuleRun;
import com.binitech.elosys.domain.signal.SignalActor;
import com.binitech.elosys.domain.signal.SignalDraft;
import com.binitech.elosys.domain.signal.SignalEvidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SignalJdbcAdapter implements SignalRepositoryPort {
  private static final String SIGNALS_OF_RULE =
      "(SELECT s.id FROM signal s JOIN rule_run r ON r.id = s.rule_run_id WHERE r.rule = ?)";

  private final JdbcClient jdbc;
  private final CopyWriter copy;
  private final IdentitySequences sequences;
  private final TransactionTemplate tx;

  public SignalJdbcAdapter(
      JdbcClient jdbc, CopyWriter copy, IdentitySequences sequences, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.copy = copy;
    this.sequences = sequences;
    this.tx = tx;
  }

  @Override
  public void resetRule(String rule) {
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("DELETE FROM signal_evidence WHERE signal_id IN " + SIGNALS_OF_RULE)
              .param(rule)
              .update();
          jdbc.sql("DELETE FROM signal_actor WHERE signal_id IN " + SIGNALS_OF_RULE)
              .param(rule)
              .update();
          jdbc.sql("DELETE FROM signal_ai_review WHERE signal_id IN " + SIGNALS_OF_RULE)
              .param(rule)
              .update();
          jdbc.sql(
                  "DELETE FROM signal WHERE rule_run_id IN (SELECT id FROM rule_run WHERE rule = ?)")
              .param(rule)
              .update();
          jdbc.sql("DELETE FROM rule_run WHERE rule = ?").param(rule).update();
        });
  }

  @Override
  public long startRun(RuleRun run) {
    return jdbc.sql(
            """
            INSERT INTO rule_run (rule, rule_version, code_commit, params, run_at, rows_generated)
            VALUES (?, ?, ?, ?::jsonb, ?, 0) RETURNING id
            """)
        .params(run.rule(), run.ruleVersion(), run.codeCommit(), run.paramsJson(), Jdbc.now())
        .query(Long.class)
        .single();
  }

  @Override
  public void insertSignals(long ruleRunId, List<SignalDraft> signals) {
    if (signals.isEmpty()) return;
    long[] ids = sequences.reserve("signal", signals.size());
    List<Object[]> signalRows = new ArrayList<>(signals.size());
    List<Object[]> actorRows = new ArrayList<>();
    List<Object[]> evidenceRows = new ArrayList<>();
    for (int i = 0; i < signals.size(); i++) {
      SignalDraft s = signals.get(i);
      long id = ids[i];
      signalRows.add(
          new Object[] {
            id, ruleRunId, s.type(), s.severity(), s.explanation(), s.amountCents(), s.pathLength()
          });
      for (SignalActor a : s.actors())
        actorRows.add(new Object[] {id, a.type(), a.actorId(), a.role()});
      for (SignalEvidence e : s.evidence())
        evidenceRows.add(new Object[] {id, e.tableName(), e.recordId()});
    }
    copy.insert(
        "signal",
        List.of(
            "id", "rule_run_id", "type", "severity", "explanation", "amount_cents", "path_length"),
        signalRows,
        false);
    copy.insert("signal_actor", List.of("signal_id", "type", "actor_id", "role"), actorRows, true);
    copy.insert(
        "signal_evidence", List.of("signal_id", "table_name", "record_id"), evidenceRows, true);
  }

  @Override
  public void finishRun(long ruleRunId, long rowsGenerated) {
    jdbc.sql("UPDATE rule_run SET rows_generated = ? WHERE id = ?")
        .params(rowsGenerated, ruleRunId)
        .update();
  }

  @Override
  public Map<String, Long> countBySeverity(long ruleRunId) {
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.sql("SELECT severity, count(*) FROM signal WHERE rule_run_id = ? GROUP BY severity")
        .param(ruleRunId)
        .query(
            rs -> {
              out.put(rs.getString(1), rs.getLong(2));
            });
    return out;
  }
}
