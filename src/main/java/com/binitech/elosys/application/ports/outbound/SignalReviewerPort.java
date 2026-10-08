package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.review.SignalReview;

public interface SignalReviewerPort {
  String model();

  SignalReview review(String systemPrompt, String userPrompt);
}
