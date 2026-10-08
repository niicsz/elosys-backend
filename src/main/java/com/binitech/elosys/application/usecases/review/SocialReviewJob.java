package com.binitech.elosys.application.usecases.review;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.PostClassifierPort;
import com.binitech.elosys.application.ports.outbound.SocialRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SocialRepositoryPort.PostToReview;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.LanguageModelUnavailableException;
import com.binitech.elosys.domain.review.PostReview;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocialReviewJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(SocialReviewJob.class);

  static final int DEFAULT_LIMIT = 100;
  static final int DEFAULT_WORKERS = 8;

  static final String SYSTEM_PROMPT =
      """
      Você classifica MANIFESTAÇÕES PÚBLICAS de políticos e candidatos brasileiros \
      no X (Twitter). O texto foi pré-selecionado por um filtro de palavras que PODEM \
      ser pejorativas — a maioria NÃO é (a palavra aparece no sentido literal, em \
      citação, em discussão sobre o próprio preconceito, reapropriada, ou é só \
      palavrão sem alvo). Seu trabalho é decidir, PELO CONTEXTO, se aquele texto \
      ataca ou deprecia um GRUPO (por orientação sexual, identidade de gênero, raça/\
      etnia, religião, deficiência, origem regional, nacionalidade, classe, corpo, \
      idade) ou uma PESSOA com xingamento desumanizante.

      Julgue APENAS as palavras do autor do post. Se for resposta/citação, o texto \
      citado é só contexto — não classifique o que o outro disse.

      Responda SEMPRE só com um objeto JSON:
      {
        "ofensivo": true | false,
        "categorias": ["lgbtfobia" | "racismo" | "misoginia" | "capacitismo" | \
      "xenofobia" | "regionalismo" | "aporofobia" | "gordofobia" | "antissemitismo" | \
      "intolerancia_religiosa" | "desumanizacao" | "etarismo_saude" | "xingamento_pessoal"],
        "severity": "low" | "medium" | "high",
        "trecho": "a parte EXATA do texto que sustenta a classificação (copie verbatim)",
        "explicacao": "1 a 3 frases, objetivas, em português"
      }

      - ofensivo=false: uso literal, citação de terceiro, discussão/denúncia do \
      preconceito, termo reapropriado pelo próprio grupo, palavrão genérico sem alvo \
      de grupo, crítica política dura sem marcador de grupo. Deixe categorias e \
      trecho vazios.
      - severity: low = insinuação/dogwhistle ou xingamento leve; medium = ofensa \
      clara a um grupo; high = incitação, desumanização explícita, defesa de \
      violência ou de discriminação.

      NUNCA afirme que houve crime. 'ofensivo' aqui quer dizer 'vale um humano \
      conferir', não 'é culpado'. Na dúvida entre false e um low fraquíssimo, \
      prefira false — o filtro anterior já é frouxo demais.""";

  private final SocialRepositoryPort social;
  private final PostClassifierPort classifier;
  private final JsonPort json;

  public SocialReviewJob(
      SocialRepositoryPort social, PostClassifierPort classifier, JsonPort json) {
    this.social = social;
    this.classifier = classifier;
    this.json = json;
  }

  @Override
  public String name() {
    return "social-review";
  }

  @Override
  public String description() {
    return "Claude classifica posts do X coletados: discurso pejorativo vs. uso legítimo"
        + " [--limit=100] [--workers=8] [--all-posts] [--handles=...] [--refresh]";
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    int limit = p.integer("limit", DEFAULT_LIMIT);
    int workers = Math.max(1, p.integer("workers", DEFAULT_WORKERS));
    String model = classifier.model();
    if (p.flag("refresh")) social.deleteReviews(model);
    List<PostToReview> targets =
        social.postsToReview(model, limit, !p.flag("all-posts"), p.list("handles"));
    LOGGER.info("{} posts para revisar ({} workers)", targets.size(), workers);

    Map<String, Integer> byCategory = new TreeMap<>();
    int reviewed = 0, flagged = 0, errors = 0;
    Progress rc = new Progress(LOGGER, "posts revisados", 25);
    try (ExecutorService pool =
        Executors.newFixedThreadPool(workers, Thread.ofVirtual().factory())) {
      CompletionService<PostReview> done = new ExecutorCompletionService<>(pool);
      Map<Future<PostReview>, PostToReview> submitted = new HashMap<>();
      Map<Long, String> prompts = new HashMap<>();
      for (PostToReview post : targets) {
        String prompt = userPrompt(post);
        prompts.put(post.id(), prompt);
        submitted.put(done.submit(() -> classifier.classify(SYSTEM_PROMPT, prompt)), post);
      }
      for (int i = 0; i < targets.size(); i++) {
        Future<PostReview> f;
        try {
          f = done.take();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new BusinessException("interrompido", e);
        }
        rc.tick();
        PostToReview post = submitted.get(f);
        PostReview review;
        try {
          review = f.get();
        } catch (ExecutionException | InterruptedException e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          if (!(cause instanceof LanguageModelUnavailableException))
            LOGGER.error("post {}", post.id(), cause);
          errors++;
          LOGGER.warn("post {}: {}", post.id(), cause.getMessage());
          if (errors >= 10 && errors > reviewed) {
            LOGGER.error("erros demais ({}) e nenhum sucesso — abortando", errors);
            pool.shutdownNow();
            return summary(reviewed, flagged, byCategory, errors, true);
          }
          continue;
        }
        social.upsertReview(post.id(), model, review, prompts.get(post.id()));
        reviewed++;
        if (review.offensive()) {
          flagged++;
          for (String c : review.categories()) byCategory.merge(c, 1, Integer::sum);
        }
      }
    }
    rc.done();
    return summary(reviewed, flagged, byCategory, errors, false);
  }

  private static Map<String, Object> summary(
      int reviewed, int flagged, Map<String, Integer> byCategory, int errors, boolean aborted) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reviewed", reviewed);
    out.put("flagged", flagged);
    out.put("by_category", byCategory);
    out.put("errors", errors);
    if (aborted) out.put("aborted", true);
    LOGGER.info("pronto: {}", out);
    return out;
  }

  private String userPrompt(PostToReview post) {
    String ctx = "Conta: @" + post.handle() + ". Tipo: " + post.kind() + ".";
    if ("reply".equals(post.kind()) && post.replyToHandle() != null)
      ctx += " (resposta a @" + post.replyToHandle() + ")";
    String terms = "";
    try {
      if (json.parse(post.matchedTermsJson()) instanceof List<?> list)
        terms = String.join(", ", list.stream().map(String::valueOf).toList());
    } catch (RuntimeException ignored) {
    }
    return ctx
        + "\nTermos do filtro que apareceram: "
        + terms
        + "\n\nTexto do post (verbatim):\n\"\"\"\n"
        + post.text()
        + "\n\"\"\"\n\nClassifique no formato JSON pedido, julgando só as palavras do autor.";
  }
}
