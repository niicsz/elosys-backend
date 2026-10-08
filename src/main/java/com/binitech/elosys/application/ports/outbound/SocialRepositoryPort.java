package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.review.PostReview;
import com.binitech.elosys.domain.social.DeclaredHandle;
import com.binitech.elosys.domain.social.SocialAccount;
import com.binitech.elosys.domain.social.SocialPostRow;
import java.util.List;
import java.util.Map;

public interface SocialRepositoryPort {
  List<DeclaredHandle> declaredXUrls(String scope);

  SocialAccount upsertAccount(
      String network, String handle, DeclaredHandle declared, long sourceId);

  void markAccountError(long accountId);

  void markAccountCrawled(long accountId, String status);

  boolean insertPostIfAbsent(SocialPostRow row);

  Map<String, Object> crawlSummary();

  List<PostToReview> postsToReview(
      String model, int limit, boolean onlyMatched, List<String> handles);

  void deleteReviews(String model);

  void upsertReview(long postId, String model, PostReview review, String prompt);

  record PostToReview(
      long id,
      String kind,
      String text,
      String replyToHandle,
      String matchedTermsJson,
      String handle) {}
}
