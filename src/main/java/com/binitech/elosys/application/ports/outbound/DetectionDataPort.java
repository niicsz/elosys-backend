package com.binitech.elosys.application.ports.outbound;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface DetectionDataPort {

  List<ExpenseCandidate> expensesMatchingAny(Collection<String> keywords);

  record ExpenseCandidate(
      long id,
      Long amountCents,
      String description,
      int year,
      String cnpj,
      String supplierName,
      Long supplierCompanyId,
      Long supplierPersonId,
      Long candidatePersonId) {}

  Map<Long, String> campaignOrgCpfs();

  void forEachDonationEdge(Consumer<LedgerEdge> consumer);

  void forEachExpenseEdge(Consumer<LedgerEdge> consumer);

  record LedgerEdge(long campaignOrgId, String counterpart, Long amountCents) {}

  String personNameByCpf(String cpf);

  String companyNameByCnpj(String cnpj);

  Long personIdByCpf(String cpf);

  Long companyIdByCnpj(String cnpj);

  List<Long> donationIdsBetween(String donorDoc, String candidateCpf, int limit);

  List<Long> expenseIdsBetween(String candidateCpf, String supplierDoc, int limit);

  List<PartnerOfSupplier> partnersOfPaidSuppliers();

  record PartnerOfSupplier(
      long partnerId,
      long companyId,
      String partnerName,
      String partnerDocMasked,
      String role,
      String entryDate) {}

  SupplierPayments supplierPayments(long companyId);

  record SupplierPayments(long count, long totalCents, long distinctPayers) {}

  boolean supplierPaidByPerson(long companyId, long personId);

  void clearCandidateSupplierPartners();

  void insertCandidateSupplierPartner(CandidateSupplierPartner row);

  record CandidateSupplierPartner(
      long personId,
      long companyId,
      long companyPartnerId,
      String matchBasis,
      String partnerRole,
      String partnerSince,
      long paymentsTotalCents,
      long paymentsCount,
      long payerCandidacies,
      boolean paidBySelf) {}

  Map<Boolean, Long> candidateSupplierPartnersByPaidBySelf();
}
