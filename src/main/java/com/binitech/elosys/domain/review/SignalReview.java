package com.binitech.elosys.domain.review;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SignalReview(
    String verdict,
    String confidence,
    String explanation,
    List<String> facts,
    String rawResponse,
    Integer tokensPrompt,
    Integer tokensCompletion) {
  public static final Set<String> VERDICTS = Set.of("bizarro", "plausivel", "inconclusivo");
  public static final Set<String> CONFIDENCES = Set.of("baixa", "media", "alta");

  public static SignalReview normalized(
      String verdict,
      String confidence,
      String explanation,
      List<String> facts,
      String rawResponse,
      Integer tokensPrompt,
      Integer tokensCompletion) {
    String v = verdict == null ? "" : verdict.strip().toLowerCase(Locale.ROOT);
    if (!VERDICTS.contains(v)) v = "inconclusivo";
    String c = confidence == null ? "" : confidence.strip().toLowerCase(Locale.ROOT);
    if (!CONFIDENCES.contains(c)) c = null;
    String e = explanation == null ? "" : explanation.strip();
    if (e.isEmpty()) e = "(modelo não devolveu explicação)";
    List<String> f = facts == null ? List.of() : facts.stream().map(String::valueOf).toList();
    return new SignalReview(v, c, e, f, rawResponse, tokensPrompt, tokensCompletion);
  }
}
