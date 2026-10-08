package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.candidate.CandidateRow;
import com.binitech.elosys.domain.candidate.RejectedCpf;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public interface CandidateRepositoryPort {
  void createStaging();

  void stage(List<CandidateRow> rows);

  void markAmbiguousCpfs();

  Set<String> rejectedCpfs();

  List<RejectedCpf> rejectedCpfDetail();

  void forEachStaged(Consumer<CandidateRow> consumer);

  int insertHistory(List<PromotedCandidate> rows);

  void dropStaging();

  long countHistory();

  Map<String, Long> personByCandidacy(int year);

  Map<String, long[]> personAndHistoryByCandidacy(int year);

  Long historyIdByCandidacy(String tseCandidacyId);

  List<OfficeHolder> officeHolders(Collection<String> offices);

  record PromotedCandidate(CandidateRow row, long personId, boolean cpfTrusted) {}

  record OfficeHolder(long personId, String ballotName, String fullName) {}
}
