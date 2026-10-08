package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.assets.CandidatePhotoRow;
import com.binitech.elosys.domain.assets.DeclaredAssetRow;
import com.binitech.elosys.domain.finance.SocialMediaRow;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface TseExtrasRepositoryPort {
  int insertSocialMedia(List<SocialMediaRow> rows);

  Map<String, Object> socialMediaSummary();

  int insertDeclaredAssets(List<DeclaredAssetRow> rows);

  Map<String, Object> declaredAssetsSummary();

  List<PersonCpfTarget> photoTargets(int limit);

  List<PersonCpfTarget> photoTargetsForYears(Collection<Integer> years, int limit);

  List<PersonCpfTarget> photoTargetsForPeople(Collection<Long> personIds);

  void insertPhotosIgnoringDuplicates(List<CandidatePhotoRow> rows);

  Map<String, Object> photoSummary();

  record PersonCpfTarget(long personId, String cpf) {}
}
