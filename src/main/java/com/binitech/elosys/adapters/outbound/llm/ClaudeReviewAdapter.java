package com.binitech.elosys.adapters.outbound.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.Usage;
import com.binitech.elosys.adapters.outbound.resilience.ResilienceFacade;
import com.binitech.elosys.adapters.outbound.resilience.TransientFailure;
import com.binitech.elosys.application.ports.outbound.PostClassifierPort;
import com.binitech.elosys.application.ports.outbound.SignalReviewerPort;
import com.binitech.elosys.config.ElosysProperties;
import com.binitech.elosys.domain.exception.LanguageModelUnavailableException;
import com.binitech.elosys.domain.review.PostReview;
import com.binitech.elosys.domain.review.SignalReview;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ClaudeReviewAdapter implements SignalReviewerPort, PostClassifierPort {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClaudeReviewAdapter.class);

  enum Verdict {
    bizarro,
    plausivel,
    inconclusivo
  }

  enum Confidence {
    baixa,
    media,
    alta
  }

  record SignalTriage(
      @JsonPropertyDescription("bizarro, plausivel ou inconclusivo") Verdict verdict,
      @JsonPropertyDescription("baixa, media ou alta") Confidence confianca,
      @JsonPropertyDescription("2 a 4 frases em português, objetivas, citando os fatos que pesaram")
          String explicacao,
      @JsonPropertyDescription("os pontos concretos citados") List<String> fatos) {}

  enum Category {
    lgbtfobia,
    racismo,
    misoginia,
    capacitismo,
    xenofobia,
    regionalismo,
    aporofobia,
    gordofobia,
    antissemitismo,
    intolerancia_religiosa,
    desumanizacao,
    etarismo_saude,
    xingamento_pessoal
  }

  enum Severity {
    low,
    medium,
    high
  }

  record PostClassification(
      @JsonPropertyDescription("true se o texto ataca/deprecia um grupo ou desumaniza uma pessoa")
          boolean ofensivo,
      @JsonPropertyDescription("categorias apontadas; vazio quando ofensivo=false")
          List<Category> categorias,
      @JsonPropertyDescription("low, medium ou high") Severity severity,
      @JsonPropertyDescription("o trecho EXATO do texto que sustenta a classificação, ou vazio")
          String trecho,
      @JsonPropertyDescription("1 a 3 frases, objetivas, em português") String explicacao) {}

  private final AnthropicClient client;
  private final ResilienceFacade resilience;
  private final String model;
  private final long maxTokens;
  private final boolean configured;
  private final ObjectMapper rawJson = new ObjectMapper();
  private final StructuredOutputConfig<SignalTriage> triageFormat =
      StructuredOutputConfig.<SignalTriage>builder().format(SignalTriage.class).build();
  private final StructuredOutputConfig<PostClassification> postFormat =
      StructuredOutputConfig.<PostClassification>builder().format(PostClassification.class).build();

  public ClaudeReviewAdapter(
      AnthropicClient client, ResilienceFacade resilience, ElosysProperties properties) {
    this.client = client;
    this.resilience = resilience;
    this.model = properties.llm().model();
    this.maxTokens = properties.llm().maxTokens();
    this.configured = properties.llm().apiKey() != null && !properties.llm().apiKey().isBlank();
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public SignalReview review(String systemPrompt, String userPrompt) {
    StructuredMessage<SignalTriage> response =
        call(() -> client.messages().create(params(systemPrompt, userPrompt, triageFormat)));
    SignalTriage t = single(response);
    Usage usage = response.usage();
    return SignalReview.normalized(
        t.verdict() == null ? null : t.verdict().name(),
        t.confianca() == null ? null : t.confianca().name(),
        t.explicacao(),
        t.fatos(),
        raw(t),
        (int) usage.inputTokens(),
        (int) usage.outputTokens());
  }

  @Override
  public PostReview classify(String systemPrompt, String userPrompt) {
    StructuredMessage<PostClassification> response =
        call(() -> client.messages().create(params(systemPrompt, userPrompt, postFormat)));
    PostClassification c = single(response);
    Usage usage = response.usage();
    return PostReview.normalized(
        c.ofensivo(),
        c.categorias() == null ? List.of() : c.categorias().stream().map(Enum::name).toList(),
        c.severity() == null ? null : c.severity().name(),
        c.trecho(),
        c.explicacao(),
        raw(c),
        (int) usage.inputTokens(),
        (int) usage.outputTokens());
  }

  private <T> StructuredMessageCreateParams<T> params(
      String systemPrompt, String userPrompt, StructuredOutputConfig<T> format) {
    return MessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .system(systemPrompt)
        .outputConfig(format)
        .addUserMessage(userPrompt)
        .build();
  }

  private <T> T call(Supplier<T> request) {
    if (!configured)
      throw new LanguageModelUnavailableException(
          "ANTHROPIC_API_KEY não definida no ambiente", null);
    return resilience.llm(
        () -> {
          try {
            return request.get();
          } catch (RateLimitException e) {
            throw new TransientFailure("rate limit da API da Anthropic", e);
          } catch (InternalServerException e) {
            throw new TransientFailure("API da Anthropic respondeu " + e.statusCode(), e);
          } catch (AnthropicIoException e) {
            throw new TransientFailure("falha de rede com a API da Anthropic", e);
          } catch (AnthropicInvalidDataException e) {
            throw new LanguageModelUnavailableException("resposta fora do schema esperado", e);
          } catch (AnthropicServiceException e) {
            if (e.statusCode() == 529 || e.statusCode() >= 500)
              throw new TransientFailure(
                  "API da Anthropic sobrecarregada (" + e.statusCode() + ")", e);
            throw new LanguageModelUnavailableException(
                "API da Anthropic recusou a requisição (" + e.statusCode() + ")", e);
          }
        });
  }

  private <T> T single(StructuredMessage<T> response) {
    StopReason stop = response.stopReason().orElse(null);
    if (StopReason.REFUSAL.equals(stop))
      throw new LanguageModelUnavailableException("o modelo recusou a análise (refusal)", null);
    if (StopReason.MAX_TOKENS.equals(stop))
      throw new LanguageModelUnavailableException("resposta cortada por max_tokens", null);
    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(block -> block.text())
        .findFirst()
        .orElseThrow(() -> new LanguageModelUnavailableException("resposta sem conteúdo", null));
  }

  private String raw(Object value) {
    try {
      return rawJson.writeValueAsString(value);
    } catch (Exception e) {
      LOGGER.debug("não foi possível serializar a resposta: {}", e.toString());
      return String.valueOf(value);
    }
  }
}
