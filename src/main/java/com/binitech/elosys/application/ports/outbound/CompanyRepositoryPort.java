package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.company.CompanyPartnerRow;
import com.binitech.elosys.domain.company.CompanyRegistryRow;
import com.binitech.elosys.domain.company.CompanyTarget;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CompanyRepositoryPort {
  Map<String, Long> idsByCnpj();

  long[] reserveIds(int count);

  void insert(List<NewCompany> companies);

  void deleteByKinds(Collection<String> kinds);

  long countByKinds(Collection<String> kinds);

  List<CompanyTarget> missingRegistryByMoney(int limit, boolean includeCampaign);

  List<CompanyTarget> missingRegistryById(int limit, boolean includeCampaign);

  List<CompanyTarget> findByCnpjs(Collection<String> cnpjs);

  void upsertRegistry(CompanyRegistryRow row);

  void fillLegalNameIfMissing(long companyId, String legalName);

  void insertPartnersIgnoringDuplicates(List<CompanyPartnerRow> partners);

  long countMissingRegistry();

  long countRegistries();

  long countPartners();

  record NewCompany(long id, String cnpj, String kind) {}
}
