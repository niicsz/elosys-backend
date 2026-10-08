package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort.IdValue;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort.NewPerson;
import com.binitech.elosys.domain.SourceValues;
import com.binitech.elosys.domain.identity.PersonMatch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IdentityResolver {
  private static final int ID_BLOCK = 10_000;
  private static final int FLUSH_EVERY = 20_000;

  private final PeopleRepositoryPort people;
  private final Map<String, Long> byVoterId = new HashMap<>();
  private final Map<String, Long> byCpf = new HashMap<>();
  private final BitSet hasCpf = new BitSet();
  private final BitSet hasVoterId = new BitSet();
  private final Deque<Long> reservedIds = new ArrayDeque<>();
  private final List<NewPerson> pendingInserts = new ArrayList<>();
  private final List<IdValue> pendingCpf = new ArrayList<>();
  private final List<IdValue> pendingVoterId = new ArrayList<>();

  public IdentityResolver(PeopleRepositoryPort people) {
    this.people = people;
    people.forEachPerson(
        p -> {
          if (p.voterId() != null) {
            byVoterId.putIfAbsent(p.voterId(), p.id());
            hasVoterId.set(Math.toIntExact(p.id()));
          }
          if (p.cpf() != null) {
            byCpf.putIfAbsent(p.cpf(), p.id());
            hasCpf.set(Math.toIntExact(p.id()));
          }
        });
  }

  public PersonMatch resolve(String cpf, String voterId, String normalizedName) {
    String cpfDigits = SourceValues.digitsOnly(cpf);
    String voterDigits = SourceValues.digitsOnly(voterId);
    boolean cpfOk = SourceValues.cpfIsValid(cpfDigits);

    Long personId = voterDigits != null ? byVoterId.get(voterDigits) : null;
    if (personId == null && cpfOk) personId = byCpf.get(cpfDigits);

    if (personId == null) {
      long id = nextId();
      pendingInserts.add(
          new NewPerson(id, cpfOk ? cpfDigits : null, cpfOk, voterDigits, normalizedName));
      if (voterDigits != null) indexVoterId(id, voterDigits);
      if (cpfOk) indexCpf(id, cpfDigits);
      maybeFlush();
      return new PersonMatch(id, cpfOk);
    }

    int bit = Math.toIntExact(personId);
    if (cpfOk && !hasCpf.get(bit)) {
      pendingCpf.add(new IdValue(personId, cpfDigits));
      indexCpf(personId, cpfDigits);
    }
    if (voterDigits != null && !hasVoterId.get(bit)) {
      pendingVoterId.add(new IdValue(personId, voterDigits));
      indexVoterId(personId, voterDigits);
    }
    maybeFlush();
    return new PersonMatch(personId, cpfOk);
  }

  public Long personByCpf(String cpf) {
    return cpf == null ? null : byCpf.get(cpf);
  }

  public void flush() {
    if (!pendingInserts.isEmpty()) {
      people.insert(List.copyOf(pendingInserts));
      pendingInserts.clear();
    }
    if (!pendingCpf.isEmpty()) {
      people.fillCpf(List.copyOf(pendingCpf));
      pendingCpf.clear();
    }
    if (!pendingVoterId.isEmpty()) {
      people.fillVoterId(List.copyOf(pendingVoterId));
      pendingVoterId.clear();
    }
  }

  private void maybeFlush() {
    if (pendingInserts.size() + pendingCpf.size() + pendingVoterId.size() >= FLUSH_EVERY) flush();
  }

  private void indexCpf(long id, String cpf) {
    byCpf.merge(cpf, id, Math::min);
    hasCpf.set(Math.toIntExact(id));
  }

  private void indexVoterId(long id, String voterId) {
    byVoterId.merge(voterId, id, Math::min);
    hasVoterId.set(Math.toIntExact(id));
  }

  private long nextId() {
    if (reservedIds.isEmpty()) {
      for (long id : people.reserveIds(ID_BLOCK)) reservedIds.add(id);
    }
    return reservedIds.removeFirst();
  }
}
