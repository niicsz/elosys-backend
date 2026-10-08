package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.review.SignalReview;
import java.util.List;
import java.util.Map;

public interface AiReviewRepositoryPort {
  void deleteReviews(String model, String rule);

  List<SignalToReview> signalsToReview(
      String rule, String model, int limit, boolean tightFirst, long minAmountCents);

  record SignalToReview(long id, String explanation) {}

  List<ActorRef> actors(long signalId);

  record ActorRef(String type, long actorId) {}

  String personName(long personId);

  String companyName(long companyId);

  Map<String, Object> candidacyBlurb(long personId);

  List<EvidenceRef> evidence(long signalId);

  record EvidenceRef(String tableName, long recordId) {}

  LedgerFact donationFact(long donationId);

  LedgerFact expenseFact(long expenseId);

  record LedgerFact(Long amountCents, Integer year, String from, String to) {}

  Map<String, Object> disproportionateExpenseFacts(long signalId);

  void upsertReview(long signalId, String model, SignalReview review, String prompt);
}
