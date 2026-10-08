package com.binitech.elosys.adapters.outbound.http;

import com.binitech.elosys.adapters.outbound.resilience.ResilienceFacade;
import com.binitech.elosys.adapters.outbound.resilience.TransientFailure;
import com.binitech.elosys.application.ports.outbound.ScraperPort;
import com.binitech.elosys.config.ElosysProperties;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.SourceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApifyScraperAdapter implements ScraperPort {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApifyScraperAdapter.class);
  private static final Set<String> FAILED = Set.of("FAILED", "ABORTED", "TIMED-OUT", "TIMED_OUT");
  private static final Duration CONCURRENCY_RETRY_WAIT = Duration.ofSeconds(30);
  private static final int CONCURRENCY_RETRIES = 40;
  private static final Duration POLL = Duration.ofSeconds(5);
  private static final int PAGE_SIZE = 1000;

  private final ResilienceFacade resilience;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private final String token;
  private final String actor;
  private final String baseUrl;

  public ApifyScraperAdapter(ResilienceFacade resilience, ElosysProperties properties) {
    this.resilience = resilience;
    this.token = properties.apify().token();
    this.actor = properties.apify().actor();
    this.baseUrl = properties.apify().baseUrl();
  }

  @Override
  public boolean configured() {
    return token != null && !token.isBlank();
  }

  @Override
  public ScrapeRun run(Map<String, Object> actorInput, int maxWaitSeconds) {
    if (!configured())
      throw new BusinessException(
          "APIFY_TOKEN não definida no ambiente (APIFY_TOKEN=apify_api_...)");
    Map<String, Object> data = start(actorInput);
    String runId = String.valueOf(data.get("id"));
    String datasetId = String.valueOf(data.get("defaultDatasetId"));
    String status = String.valueOf(data.getOrDefault("status", "RUNNING"));
    long waited = 0;
    while (!status.equals("SUCCEEDED")) {
      if (FAILED.contains(status))
        throw new SourceUnavailableException(
            "execução " + runId + " terminou como " + status, null);
      if (waited >= maxWaitSeconds)
        throw new SourceUnavailableException(
            "execução " + runId + " ainda em " + status + " após " + maxWaitSeconds + "s", null);
      sleep(POLL);
      waited += POLL.toSeconds();
      try {
        Map<String, Object> run = get("/actor-runs/" + runId, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) run.get("data");
        status = String.valueOf(d.get("status"));
      } catch (RuntimeException e) {
        status = "RUNNING";
      }
    }
    return new ScrapeRun(runId, items(datasetId));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> start(Map<String, Object> input) {
    String url = baseUrl + "/acts/" + actor + "/runs?token=" + enc(token) + "&memory=512";
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> r =
          resilience.apify(
              "apify start",
              () ->
                  send(
                      HttpRequest.newBuilder(URI.create(url))
                          .timeout(Duration.ofSeconds(60))
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)))
                          .build()));
      if (r.statusCode() == 402 && r.body().toLowerCase(Locale.ROOT).contains("concurrent")) {
        if (attempt >= CONCURRENCY_RETRIES)
          throw new SourceUnavailableException(
              "limite de execuções concorrentes da Apify — reduza --workers", null);
        LOGGER.info(
            "Apify no limite de execuções concorrentes; aguardando {}", CONCURRENCY_RETRY_WAIT);
        sleep(CONCURRENCY_RETRY_WAIT);
        continue;
      }
      if (r.statusCode() != 200 && r.statusCode() != 201)
        throw new BusinessException(
            "Apify start HTTP " + r.statusCode() + ": " + truncate(r.body()));
      Map<String, Object> body = json.readValue(r.body(), new TypeReference<>() {});
      return (Map<String, Object>) body.get("data");
    }
  }

  private List<Map<String, Object>> items(String datasetId) {
    List<Map<String, Object>> items = new ArrayList<>();
    int offset = 0;
    while (true) {
      List<Map<String, Object>> page =
          get(
              "/datasets/"
                  + datasetId
                  + "/items?clean=true&offset="
                  + offset
                  + "&limit="
                  + PAGE_SIZE,
              new TypeReference<>() {});
      if (page == null || page.isEmpty()) break;
      items.addAll(page);
      if (page.size() < PAGE_SIZE) break;
      offset += page.size();
    }
    return items;
  }

  private <T> T get(String path, TypeReference<T> type) {
    String sep = path.contains("?") ? "&" : "?";
    URI uri = URI.create(baseUrl + path + sep + "token=" + enc(token));
    HttpResponse<String> r =
        resilience.apify(
            "apify " + path.replaceAll("\\?.*", ""),
            () -> {
              HttpResponse<String> resp =
                  send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(180)).GET().build());
              if (resp.statusCode() >= 500 || resp.statusCode() == 429)
                throw new TransientFailure("Apify HTTP " + resp.statusCode());
              return resp;
            });
    if (r.statusCode() != 200)
      throw new BusinessException("Apify HTTP " + r.statusCode() + ": " + truncate(r.body()));
    return json.readValue(r.body(), type);
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      HttpResponse<String> r =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (r.statusCode() >= 500) throw new TransientFailure("Apify HTTP " + r.statusCode());
      return r;
    } catch (IOException e) {
      throw new TransientFailure("erro de rede com a Apify: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException("chamada à Apify interrompida", e);
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException("interrompido", e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static String truncate(String s) {
    return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
  }
}
