package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PeopleJdbcAdapter implements PeopleRepositoryPort {
  private final JdbcClient jdbc;
  private final JdbcTemplate template;
  private final IdentitySequences sequences;
  private final StreamingQueries streaming;

  public PeopleJdbcAdapter(
      JdbcClient jdbc,
      JdbcTemplate template,
      IdentitySequences sequences,
      StreamingQueries streaming) {
    this.jdbc = jdbc;
    this.template = template;
    this.sequences = sequences;
    this.streaming = streaming;
  }

  @Override
  public void forEachPerson(Consumer<PersonKeys> consumer) {
    streaming.forEach(
        "SELECT id, cpf, voter_id FROM people ORDER BY id",
        rs ->
            consumer.accept(
                new PersonKeys(rs.getLong("id"), rs.getString("cpf"), rs.getString("voter_id"))));
  }

  @Override
  public long[] reserveIds(int count) {
    return sequences.reserve("people", count);
  }

  @Override
  public void insert(List<NewPerson> people) {
    OffsetDateTime now = Jdbc.now();
    template.batchUpdate(
        """
        INSERT INTO people (id, cpf, cpf_trusted, voter_id, canonical_name, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        people,
        5_000,
        (ps, p) -> {
          ps.setLong(1, p.id());
          Jdbc.setString(ps, 2, p.cpf());
          ps.setBoolean(3, p.cpfTrusted());
          Jdbc.setString(ps, 4, p.voterId());
          Jdbc.setString(ps, 5, p.canonicalName());
          ps.setObject(6, now);
        });
  }

  @Override
  public void fillCpf(List<IdValue> updates) {
    template.batchUpdate(
        "UPDATE people SET cpf = ?, cpf_trusted = true WHERE id = ? AND cpf IS NULL",
        updates,
        5_000,
        (ps, u) -> {
          ps.setString(1, u.value());
          ps.setLong(2, u.id());
        });
  }

  @Override
  public void fillVoterId(List<IdValue> updates) {
    template.batchUpdate(
        "UPDATE people SET voter_id = ? WHERE id = ? AND voter_id IS NULL",
        updates,
        5_000,
        (ps, u) -> {
          ps.setString(1, u.value());
          ps.setLong(2, u.id());
        });
  }

  @Override
  public List<PersonCpf> findWithCpfByCanonicalName(String canonicalName) {
    return jdbc.sql("SELECT id, cpf FROM people WHERE canonical_name = ? AND cpf IS NOT NULL")
        .param(canonicalName)
        .query((rs, i) -> new PersonCpf(rs.getLong("id"), rs.getString("cpf")))
        .list();
  }
}
