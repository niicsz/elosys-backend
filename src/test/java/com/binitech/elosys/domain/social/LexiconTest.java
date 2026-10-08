package com.binitech.elosys.domain.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.binitech.elosys.domain.social.Lexicon.Term;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexiconTest {
  private final Lexicon lexicon =
      new Lexicon(
          List.of(
              new Term("a", "macaco", "media", null),
              new Term("a", "kit gay", "alta", null),
              new Term("b", "Macaco", "alta", null),
              new Term("b", "fresco", "baixa", null)));

  @Test
  void deduplicatesCaseInsensitively() {
    assertThat(lexicon.allTerms())
        .extracting(Term::text)
        .containsExactly("macaco", "kit gay", "fresco");
  }

  @Test
  void matchesWholeWordsOnly() {
    assertThat(lexicon.matches("Que MACACO!")).containsExactly("macaco");
    assertThat(lexicon.matches("macacos")).isEmpty();
    assertThat(lexicon.matches("o kit gay de novo")).containsExactly("kit gay");
  }

  @Test
  void filtersByMinimumWeight() {
    assertThat(lexicon.searchTerms("media")).containsExactly("macaco", "kit gay");
    assertThat(lexicon.searchTerms("baixa")).hasSize(3);
  }

  @Test
  void buildsQueriesWithinTheSearchLengthLimit() {
    List<String> queries = lexicon.buildQueries("@fulano", "baixa", "-filter:retweets");
    assertThat(queries)
        .containsExactly("from:fulano (macaco OR \"kit gay\" OR fresco) -filter:retweets");
  }
}
