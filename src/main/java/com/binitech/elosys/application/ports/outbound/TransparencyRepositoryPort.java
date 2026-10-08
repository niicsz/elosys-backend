package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.earmark.EarmarkBeneficiaryRow;
import com.binitech.elosys.domain.earmark.EarmarkRow;
import com.binitech.elosys.domain.sanction.SanctionRow;
import java.util.List;
import java.util.Map;

public interface TransparencyRepositoryPort {
  int insertSanctions(List<SanctionRow> rows);

  Map<String, Object> sanctionSummary();

  int insertEarmarks(List<EarmarkRow> rows);

  int insertEarmarkBeneficiaries(List<EarmarkBeneficiaryRow> rows);

  Map<String, Object> earmarkSummary();
}
