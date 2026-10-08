package com.binitech.elosys.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.domain.identity.PersonMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class IdentityResolverTest {

  static final class InMemoryPeople implements PeopleRepositoryPort {
    final List<long[]> ids = new ArrayList<>();
    final List<NewPerson> inserted = new ArrayList<>();
    final List<IdValue> cpfFills = new ArrayList<>();
    final List<IdValue> voterFills = new ArrayList<>();
    final List<PersonKeys> existing = new ArrayList<>();
    long next = 1;

    @Override
    public void forEachPerson(Consumer<PersonKeys> consumer) {
      existing.forEach(consumer);
    }

    @Override
    public long[] reserveIds(int count) {
      long[] out = LongStream.range(next, next + count).toArray();
      next += count;
      return out;
    }

    @Override
    public void insert(List<NewPerson> people) {
      inserted.addAll(people);
    }

    @Override
    public void fillCpf(List<IdValue> updates) {
      cpfFills.addAll(updates);
    }

    @Override
    public void fillVoterId(List<IdValue> updates) {
      voterFills.addAll(updates);
    }

    @Override
    public List<PersonCpf> findWithCpfByCanonicalName(String canonicalName) {
      return List.of();
    }
  }

  @Test
  void matchesByVoterIdFirstThenCpfAndFillsMissingKeys() {
    InMemoryPeople people = new InMemoryPeople();
    IdentityResolver resolver = new IdentityResolver(people);

    PersonMatch first = resolver.resolve(null, "012345678901", "FULANO DE TAL");
    PersonMatch second = resolver.resolve("529.982.247-25", "012345678901", "FULANO DE TAL");
    PersonMatch third = resolver.resolve("52998224725", null, "FULANO DE TAL");
    resolver.flush();

    assertThat(second.personId()).isEqualTo(first.personId());
    assertThat(third.personId()).isEqualTo(first.personId());
    assertThat(first.cpfTrusted()).isFalse();
    assertThat(second.cpfTrusted()).isTrue();
    assertThat(people.inserted).hasSize(1);
    assertThat(people.cpfFills)
        .containsExactly(new PeopleRepositoryPort.IdValue(first.personId(), "52998224725"));
  }

  @Test
  void invalidCpfIsNeverUsedForMatching() {
    InMemoryPeople people = new InMemoryPeople();
    IdentityResolver resolver = new IdentityResolver(people);

    PersonMatch a = resolver.resolve("11111111111", null, "A");
    PersonMatch b = resolver.resolve("11111111111", null, "B");
    resolver.flush();

    assertThat(a.personId()).isNotEqualTo(b.personId());
    assertThat(people.inserted).allSatisfy(p -> assertThat(p.cpf()).isNull());
  }

  @Test
  void existingPeopleAreIndexedWithLowestIdWinning() {
    InMemoryPeople people = new InMemoryPeople();
    people.existing.add(new PeopleRepositoryPort.PersonKeys(5, "52998224725", null));
    people.existing.add(new PeopleRepositoryPort.PersonKeys(9, "52998224725", null));
    people.next = 10;
    IdentityResolver resolver = new IdentityResolver(people);

    assertThat(resolver.resolve("52998224725", null, "X").personId()).isEqualTo(5);
  }
}
