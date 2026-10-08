package com.binitech.elosys.domain.social;

public record SocialPostRow(
    long socialAccountId,
    String externalId,
    String kind,
    String lang,
    String text,
    String inReplyToExternal,
    String replyToHandle,
    String postedAt,
    Long likeCount,
    Long repostCount,
    Long replyCount,
    String url,
    String matchedTermsJson,
    String matchedQuery,
    String apifyRunId,
    String rawJson,
    String rawSha256) {}
