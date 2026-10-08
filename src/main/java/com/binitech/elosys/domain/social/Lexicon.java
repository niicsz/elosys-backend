package com.binitech.elosys.domain.social;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class Lexicon {
  public static final Map<String, Integer> WEIGHT_RANK = Map.of("baixa", 0, "media", 1, "alta", 2);
  private static final int MAX_QUERY_LEN = 480;

  private final List<Term> terms;
  private final List<CompiledTerm> compiled;

  public record Term(String category, String text, String weight, String note) {}

  private record CompiledTerm(String term, Pattern pattern) {}

  public Lexicon(List<Term> entries) {
    Set<String> seen = new HashSet<>();
    List<Term> unique = new ArrayList<>();
    for (Term t : entries) {
      if (seen.add(t.text().toLowerCase(Locale.ROOT))) unique.add(t);
    }
    this.terms = List.copyOf(unique);
    List<CompiledTerm> c = new ArrayList<>();
    for (Term t : terms) {
      String lower = t.text().toLowerCase(Locale.ROOT);
      String regex =
          lower.contains(" ") ? Pattern.quote(lower) : "\\b" + Pattern.quote(lower) + "\\b";
      c.add(new CompiledTerm(lower, Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS)));
    }
    this.compiled = List.copyOf(c);
  }

  public List<Term> allTerms() {
    return terms;
  }

  public List<String> searchTerms(String minWeight) {
    int floor = WEIGHT_RANK.getOrDefault(minWeight, 0);
    return terms.stream()
        .filter(t -> WEIGHT_RANK.getOrDefault(t.weight(), 0) >= floor)
        .map(Term::text)
        .toList();
  }

  public List<String> matches(String text) {
    String low = text.toLowerCase(Locale.ROOT);
    TreeSet<String> out = new TreeSet<>();
    for (CompiledTerm t : compiled) if (t.pattern().matcher(low).find()) out.add(t.term());
    return List.copyOf(out);
  }

  public List<String> buildQueries(String handle, String minWeight, String extra) {
    String h = handle.startsWith("@") ? handle.substring(1) : handle;
    String prefix = "from:" + h + " ";
    String suffix = (" " + extra).stripTrailing();
    int budget = MAX_QUERY_LEN - prefix.length() - suffix.length() - 2;
    List<String> queries = new ArrayList<>();
    List<String> current = new ArrayList<>();
    int currentLen = 0;
    for (String term : searchTerms(minWeight)) {
      String token = term.contains(" ") ? "\"" + term + "\"" : term;
      int add = token.length() + (current.isEmpty() ? 0 : 4);
      if (!current.isEmpty() && currentLen + add > budget) {
        queries.add(prefix + "(" + String.join(" OR ", current) + ")" + suffix);
        current = new ArrayList<>();
        currentLen = 0;
        add = token.length();
      }
      current.add(token);
      currentLen += add;
    }
    if (!current.isEmpty()) queries.add(prefix + "(" + String.join(" OR ", current) + ")" + suffix);
    return queries;
  }
}
