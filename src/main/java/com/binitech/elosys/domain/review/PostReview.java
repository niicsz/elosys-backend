package com.binitech.elosys.domain.review;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record PostReview(
    boolean offensive,
    List<String> categories,
    String severity,
    String quote,
    String explanation,
    String rawResponse,
    Integer tokensPrompt,
    Integer tokensCompletion) {
  public static final Set<String> SEVERITIES = Set.of("low", "medium", "high");
  public static final Set<String> CATEGORIES =
      Set.of(
          "lgbtfobia",
          "racismo",
          "misoginia",
          "capacitismo",
          "xenofobia",
          "regionalismo",
          "aporofobia",
          "gordofobia",
          "antissemitismo",
          "intolerancia_religiosa",
          "etarismo_saude",
          "desumanizacao",
          "xingamento_pessoal");

  public static PostReview normalized(
      boolean offensive,
      List<String> categories,
      String severity,
      String quote,
      String explanation,
      String rawResponse,
      Integer tokensPrompt,
      Integer tokensCompletion) {
    List<String> cats =
        categories == null
            ? List.of()
            : categories.stream()
                .filter(Objects::nonNull)
                .map(c -> c.strip().toLowerCase(Locale.ROOT))
                .filter(CATEGORIES::contains)
                .toList();
    String sev = severity == null ? "" : severity.strip().toLowerCase(Locale.ROOT);
    if (!SEVERITIES.contains(sev)) sev = null;
    String q = quote == null || quote.strip().isEmpty() ? null : quote.strip();
    String e = explanation == null ? "" : explanation.strip();
    if (!offensive) {
      cats = List.of();
      q = null;
    }
    if (offensive && sev == null) sev = "low";
    return new PostReview(
        offensive,
        cats,
        sev,
        q,
        e.isEmpty() ? "(sem explicação)" : e,
        rawResponse,
        tokensPrompt,
        tokensCompletion);
  }
}
