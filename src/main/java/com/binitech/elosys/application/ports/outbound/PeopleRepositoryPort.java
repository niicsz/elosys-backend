package com.binitech.elosys.application.ports.outbound;

import java.util.List;
import java.util.function.Consumer;

public interface PeopleRepositoryPort {
  void forEachPerson(Consumer<PersonKeys> consumer);

  long[] reserveIds(int count);

  void insert(List<NewPerson> people);

  void fillCpf(List<IdValue> updates);

  void fillVoterId(List<IdValue> updates);

  List<PersonCpf> findWithCpfByCanonicalName(String canonicalName);

  record PersonKeys(long id, String cpf, String voterId) {}

  record NewPerson(long id, String cpf, boolean cpfTrusted, String voterId, String canonicalName) {}

  record IdValue(long id, String value) {}

  record PersonCpf(long id, String cpf) {}
}
