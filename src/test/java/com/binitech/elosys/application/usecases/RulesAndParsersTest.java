package com.binitech.elosys.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import com.binitech.elosys.application.usecases.collectors.SocialXJob;
import com.binitech.elosys.domain.review.PostReview;
import com.binitech.elosys.domain.review.SignalReview;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesAndParsersTest {

  @Test
  void normalizesDeclaredXHandles() {
    assertThat(SocialXJob.normalizeHandle("https://twitter.com/adriventurasp"))
        .isEqualTo("adriventurasp");
    assertThat(SocialXJob.normalizeHandle("www.twitter.com/deputadoserafim"))
        .isEqualTo("deputadoserafim");
    assertThat(SocialXJob.normalizeHandle("https://x.com/adriana_accorsi?s=21&t=abc"))
        .isEqualTo("adriana_accorsi");
    assertThat(SocialXJob.normalizeHandle("https://twitter.com/@motta_afonso"))
        .isEqualTo("motta_afonso");
    assertThat(SocialXJob.normalizeHandle("http://www.twitter.com.br/draalehaber"))
        .isEqualTo("draalehaber");
    assertThat(SocialXJob.normalizeHandle("X.COM/ALCEU_ALCEUMOREIRA")).isEqualTo("alceu_alceumore");
    assertThat(SocialXJob.normalizeHandle("https://twitter.com/home")).isNull();
    assertThat(SocialXJob.normalizeHandle("https://instagram.com/foo")).isNull();
  }

  @Test
  void signalReviewFallsBackToInconclusive() {
    SignalReview r = SignalReview.normalized(" BIZARRO ", "talvez", " ", null, "{}", 10, 5);
    assertThat(r.verdict()).isEqualTo("bizarro");
    assertThat(r.confidence()).isNull();
    assertThat(r.explanation()).isEqualTo("(modelo não devolveu explicação)");
    assertThat(SignalReview.normalized("???", null, "x", List.of(), "{}", 1, 1).verdict())
        .isEqualTo("inconclusivo");
  }

  @Test
  void postReviewDropsCategoriesWhenNotOffensive() {
    PostReview notOffensive =
        PostReview.normalized(false, List.of("racismo"), "high", "trecho", "ok", "{}", 1, 1);
    assertThat(notOffensive.categories()).isEmpty();
    assertThat(notOffensive.quote()).isNull();

    PostReview offensive =
        PostReview.normalized(true, List.of("RACISMO", "inventada"), null, " x ", "", "{}", 1, 1);
    assertThat(offensive.categories()).containsExactly("racismo");
    assertThat(offensive.severity()).isEqualTo("low");
    assertThat(offensive.explanation()).isEqualTo("(sem explicação)");
  }

  @Test
  void disproportionateExpenseCategoryAndStats() throws Exception {
    Class<?> job =
        Class.forName("com.binitech.elosys.application.usecases.rules.DisproportionateExpenseJob");
    Method categoryFor = job.getDeclaredMethod("categoryFor", String.class);
    categoryFor.setAccessible(true);
    assertThat(categoryFor.invoke(null, "COMPRA DE LAPISEIRAS")).isEqualTo("LAPIS");
    assertThat(categoryFor.invoke(null, "CRACHÁ PVC")).isEqualTo("CRACHA");
    assertThat(categoryFor.invoke(null, "caneta")).isNull();

    Method stats = job.getDeclaredMethod("stats", List.class);
    stats.setAccessible(true);
    List<Long> amounts =
        java.util.stream.LongStream.rangeClosed(1, 20).map(i -> i * 1000).boxed().toList();
    Object s = stats.invoke(null, amounts);
    Method mediumFloor = s.getClass().getDeclaredMethod("mediumFloor");
    mediumFloor.setAccessible(true);
    assertThat(mediumFloor.invoke(s)).isEqualTo(157_500L);
  }
}
