package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort.NewCompany;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public final class CompanyResolver {
  private static final int ID_BLOCK = 10_000;

  private final CompanyRepositoryPort companies;
  private final Map<String, Long> idsByCnpj;
  private final Deque<Long> reservedIds = new ArrayDeque<>();
  private final List<NewCompany> pending = new ArrayList<>();

  public CompanyResolver(CompanyRepositoryPort companies) {
    this.companies = companies;
    this.idsByCnpj = companies.idsByCnpj();
  }

  public long getOrCreate(String cnpj, String kind) {
    Long id = idsByCnpj.get(cnpj);
    if (id != null) return id;
    long created = nextId();
    idsByCnpj.put(cnpj, created);
    pending.add(new NewCompany(created, cnpj, kind));
    return created;
  }

  public Long find(String cnpj) {
    return cnpj == null ? null : idsByCnpj.get(cnpj);
  }

  public void flush() {
    if (pending.isEmpty()) return;
    companies.insert(List.copyOf(pending));
    pending.clear();
  }

  private long nextId() {
    if (reservedIds.isEmpty()) {
      for (long id : companies.reserveIds(ID_BLOCK)) reservedIds.add(id);
    }
    return reservedIds.removeFirst();
  }
}
