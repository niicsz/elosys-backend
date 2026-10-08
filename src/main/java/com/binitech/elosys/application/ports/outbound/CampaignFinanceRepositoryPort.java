package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.finance.CampaignOrgRow;
import com.binitech.elosys.domain.finance.DonationRow;
import com.binitech.elosys.domain.finance.ExpenseRow;
import com.binitech.elosys.domain.finance.PaymentRow;
import java.util.List;
import java.util.Map;

public interface CampaignFinanceRepositoryPort {
  int insertOrgs(List<CampaignOrgRow> rows);

  int insertDonations(List<DonationRow> rows);

  int insertExpenses(List<ExpenseRow> rows);

  int insertPayments(List<PaymentRow> rows);

  void linkDonationsToOrg(int year);

  void linkExpensesToOrg(int year);

  void linkPaymentsToExpense(int year);

  void rebuildPersonSearch();

  Map<String, Object> summary();
}
