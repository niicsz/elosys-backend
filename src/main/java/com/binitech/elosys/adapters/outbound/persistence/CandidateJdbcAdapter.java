package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.domain.candidate.CandidateRow;
import com.binitech.elosys.domain.candidate.RejectedCpf;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class CandidateJdbcAdapter implements CandidateRepositoryPort {
  private static final List<String> STAGE_COLUMNS =
      List.of(
          "cpf",
          "voter_id",
          "ballot_name",
          "full_name",
          "normalized_name",
          "tse_candidacy_id",
          "year",
          "election_type",
          "round",
          "office",
          "candidate_number",
          "party_abbr",
          "party_name",
          "party_number",
          "state",
          "electoral_unit",
          "municipality",
          "candidacy_status",
          "candidacy_status_detail",
          "result",
          "birth_date",
          "gender",
          "education",
          "marital_status",
          "race",
          "occupation",
          "provenance_id");

  private static final List<String> HISTORY_COLUMNS;

  static {
    var cols = new java.util.ArrayList<String>();
    cols.add("person_id");
    cols.add("cpf_trusted");
    cols.addAll(STAGE_COLUMNS);
    cols.add("collected_at");
    HISTORY_COLUMNS = List.copyOf(cols);
  }

  private final JdbcClient jdbc;
  private final CopyWriter copy;
  private final StreamingQueries streaming;

  public CandidateJdbcAdapter(JdbcClient jdbc, CopyWriter copy, StreamingQueries streaming) {
    this.jdbc = jdbc;
    this.copy = copy;
    this.streaming = streaming;
  }

  @Override
  public void createStaging() {
    jdbc.sql(
            """
            CREATE UNLOGGED TABLE IF NOT EXISTS stg_candidate (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                cpf text, voter_id text, ballot_name text, full_name text, normalized_name text,
                tse_candidacy_id text, year integer, election_type text, round integer,
                office text, candidate_number text, party_abbr text, party_name text,
                party_number text, state text, electoral_unit text, municipality text,
                candidacy_status text, candidacy_status_detail text, result text,
                birth_date text, gender text, education text, marital_status text, race text,
                occupation text, provenance_id bigint
            )
            """)
        .update();
  }

  @Override
  public void stage(List<CandidateRow> rows) {
    copy.insert(
        "stg_candidate", STAGE_COLUMNS, rows.stream().map(r -> stageValues(r)).toList(), false);
  }

  @Override
  public void markAmbiguousCpfs() {
    OffsetDateTime now = Jdbc.now();
    jdbc.sql(
            """
            INSERT INTO rejected_cpf (cpf, reason, distinct_voter_ids, distinct_names, recorded_at)
            SELECT cpf, 'multiple_voter_ids', count(DISTINCT voter_id),
                   count(DISTINCT normalized_name), ?
            FROM stg_candidate WHERE cpf IS NOT NULL AND voter_id IS NOT NULL
            GROUP BY cpf HAVING count(DISTINCT voter_id) > 1
            ON CONFLICT DO NOTHING
            """)
        .param(now)
        .update();
    jdbc.sql(
            """
            INSERT INTO rejected_cpf (cpf, reason, distinct_voter_ids, distinct_names, recorded_at)
            SELECT DISTINCT cpf, 'voter_id_multiple_cpf', 1, 1, ?::timestamptz
            FROM stg_candidate WHERE cpf IS NOT NULL AND voter_id IN (
              SELECT voter_id FROM stg_candidate
              WHERE cpf IS NOT NULL AND voter_id IS NOT NULL
              GROUP BY voter_id HAVING count(DISTINCT cpf) > 1)
            ON CONFLICT DO NOTHING
            """)
        .param(now)
        .update();
  }

  @Override
  public Set<String> rejectedCpfs() {
    return new HashSet<>(jdbc.sql("SELECT cpf FROM rejected_cpf").query(String.class).list());
  }

  @Override
  public List<RejectedCpf> rejectedCpfDetail() {
    return jdbc.sql(
            "SELECT cpf, reason, distinct_voter_ids, distinct_names FROM rejected_cpf ORDER BY cpf")
        .query(
            (rs, i) ->
                new RejectedCpf(
                    rs.getString("cpf"),
                    rs.getString("reason"),
                    rs.getInt("distinct_voter_ids"),
                    rs.getInt("distinct_names")))
        .list();
  }

  @Override
  public void forEachStaged(Consumer<CandidateRow> consumer) {
    streaming.forEach(
        "SELECT * FROM stg_candidate ORDER BY id", rs -> consumer.accept(fromStage(rs)));
  }

  @Override
  public int insertHistory(List<PromotedCandidate> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "politician_history",
        HISTORY_COLUMNS,
        rows.stream()
            .map(
                p -> {
                  Object[] stage = stageValues(p.row());
                  Object[] out = new Object[stage.length + 3];
                  out[0] = p.personId();
                  out[1] = p.cpfTrusted();
                  System.arraycopy(stage, 0, out, 2, stage.length);
                  out[out.length - 1] = now;
                  return out;
                })
            .toList(),
        true);
  }

  @Override
  public void dropStaging() {
    jdbc.sql("DROP TABLE IF EXISTS stg_candidate").update();
  }

  @Override
  public long countHistory() {
    return jdbc.sql("SELECT count(*) FROM politician_history").query(Long.class).single();
  }

  @Override
  public Map<String, Long> personByCandidacy(int year) {
    Map<String, Long> out = new HashMap<>();
    streaming.forEach(
        "SELECT tse_candidacy_id, person_id FROM politician_history WHERE year = ?",
        rs -> out.put(rs.getString(1), rs.getLong(2)),
        year);
    return out;
  }

  @Override
  public Map<String, long[]> personAndHistoryByCandidacy(int year) {
    Map<String, long[]> out = new HashMap<>();
    streaming.forEach(
        "SELECT id, tse_candidacy_id, person_id FROM politician_history WHERE year = ?",
        rs -> out.put(rs.getString(2), new long[] {rs.getLong(3), rs.getLong(1)}),
        year);
    return out;
  }

  @Override
  public Long historyIdByCandidacy(String tseCandidacyId) {
    return jdbc.sql("SELECT id FROM politician_history WHERE tse_candidacy_id = ? LIMIT 1")
        .param(tseCandidacyId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  @Override
  public List<OfficeHolder> officeHolders(Collection<String> offices) {
    return jdbc.sql(
            """
            SELECT DISTINCT person_id, ballot_name, full_name FROM politician_history
            WHERE office = ANY(?)
            """)
        .param(offices.toArray(String[]::new))
        .query(
            (rs, i) ->
                new OfficeHolder(
                    rs.getLong("person_id"),
                    rs.getString("ballot_name"),
                    rs.getString("full_name")))
        .list();
  }

  private static Object[] stageValues(CandidateRow r) {
    return new Object[] {
      r.cpf(),
      r.voterId(),
      r.ballotName(),
      r.fullName(),
      r.normalizedName(),
      r.tseCandidacyId(),
      r.year(),
      r.electionType(),
      r.round(),
      r.office(),
      r.candidateNumber(),
      r.partyAbbr(),
      r.partyName(),
      r.partyNumber(),
      r.state(),
      r.electoralUnit(),
      r.municipality(),
      r.candidacyStatus(),
      r.candidacyStatusDetail(),
      r.result(),
      r.birthDate(),
      r.gender(),
      r.education(),
      r.maritalStatus(),
      r.race(),
      r.occupation(),
      r.provenanceId()
    };
  }

  private static CandidateRow fromStage(ResultSet rs) throws SQLException {
    return new CandidateRow(
        rs.getString("cpf"),
        rs.getString("voter_id"),
        rs.getString("ballot_name"),
        rs.getString("full_name"),
        rs.getString("normalized_name"),
        rs.getString("tse_candidacy_id"),
        rs.getInt("year"),
        rs.getString("election_type"),
        Jdbc.getInt(rs, "round"),
        rs.getString("office"),
        rs.getString("candidate_number"),
        rs.getString("party_abbr"),
        rs.getString("party_name"),
        rs.getString("party_number"),
        rs.getString("state"),
        rs.getString("electoral_unit"),
        rs.getString("municipality"),
        rs.getString("candidacy_status"),
        rs.getString("candidacy_status_detail"),
        rs.getString("result"),
        rs.getString("birth_date"),
        rs.getString("gender"),
        rs.getString("education"),
        rs.getString("marital_status"),
        rs.getString("race"),
        rs.getString("occupation"),
        rs.getLong("provenance_id"));
  }
}
