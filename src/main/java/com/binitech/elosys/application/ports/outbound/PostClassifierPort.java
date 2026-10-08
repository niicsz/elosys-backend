package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.review.PostReview;

public interface PostClassifierPort {
  String model();

  PostReview classify(String systemPrompt, String userPrompt);
}
