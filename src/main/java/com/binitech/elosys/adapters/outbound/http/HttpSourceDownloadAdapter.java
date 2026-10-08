package com.binitech.elosys.adapters.outbound.http;

import com.binitech.elosys.adapters.outbound.resilience.ResilienceFacade;
import com.binitech.elosys.adapters.outbound.resilience.TransientFailure;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort;
import com.binitech.elosys.config.ElosysProperties;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.SourceBlockedException;
import com.binitech.elosys.domain.exception.SourceNotFoundException;
import com.binitech.elosys.domain.provenance.DownloadResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class HttpSourceDownloadAdapter implements SourceDownloadPort {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/131.0.0.0 Safari/537.36";
  private static final String ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en;q=0.8";

  private final ResilienceFacade resilience;
  private final HttpClient client;
  private final Duration readTimeout;
  private final String curlImpersonate;

  public HttpSourceDownloadAdapter(ResilienceFacade resilience, ElosysProperties properties) {
    this.resilience = resilience;
    ElosysProperties.Download download = properties.download();
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(download.connectTimeout())
            .build();
    this.readTimeout = download.readTimeout();
    String curl = download.curlImpersonatePath();
    this.curlImpersonate = curl == null || curl.isBlank() ? null : curl;
  }

  @Override
  public DownloadResult download(String url, Path destination, SourceProfile profile) {
    return protect(profile, url, () -> fetchToFile(url, destination));
  }

  @Override
  public String fetchText(String url, SourceProfile profile) {
    return protect(profile, url, () -> fetchString(url));
  }

  private <T> T protect(SourceProfile profile, String url, Supplier<T> call) {
    return switch (profile) {
      case BULK -> resilience.bulkDownload(url, call);
      case LOOKUP -> resilience.lookup(url, call);
    };
  }

  private DownloadResult fetchToFile(String url, Path destination) {
    if (curlImpersonate != null) return curlToFile(url, destination);
    Path partial = destination.resolveSibling(destination.getFileName() + ".part");
    try {
      Files.createDirectories(destination.toAbsolutePath().getParent());
      HttpResponse<InputStream> response =
          client.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
      int status = response.statusCode();
      try (InputStream body = response.body()) {
        check(url, status);
        Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
      return new DownloadResult(status, response.headers().firstValue("Content-Type").orElse(null));
    } catch (IOException e) {
      throw new TransientFailure("erro de rede em " + url + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException("download interrompido: " + url, e);
    } finally {
      try {
        Files.deleteIfExists(partial);
      } catch (IOException ignored) {
      }
    }
  }

  private String fetchString(String url) {
    if (curlImpersonate != null) {
      try {
        Path tmp = Files.createTempFile("elosys-", ".txt");
        try {
          curlToFile(url, tmp);
          return Files.readString(tmp, StandardCharsets.UTF_8);
        } finally {
          Files.deleteIfExists(tmp);
        }
      } catch (IOException e) {
        throw new TransientFailure("erro lendo resposta de " + url, e);
      }
    }
    try {
      HttpResponse<String> response =
          client.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      check(url, response.statusCode());
      return response.body();
    } catch (IOException e) {
      throw new TransientFailure("erro de rede em " + url + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException("requisição interrompida: " + url, e);
    }
  }

  private HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(readTimeout)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Language", ACCEPT_LANGUAGE)
        .GET()
        .build();
  }

  private static void check(String url, int status) {
    if (status >= 500 || status == 429) throw new TransientFailure("HTTP " + status + " em " + url);
    if (status == 403) throw new SourceBlockedException(url);
    if (status == 404) throw new SourceNotFoundException("HTTP 404 em " + url);
    if (status >= 400) throw new BusinessException("HTTP " + status + " em " + url);
  }

  private DownloadResult curlToFile(String url, Path destination) {
    List<String> command = new ArrayList<>();
    command.add(curlImpersonate);
    command.addAll(
        List.of(
            "-sS",
            "-L",
            "--max-time",
            String.valueOf(readTimeout.toSeconds()),
            "-H",
            "Accept-Language: " + ACCEPT_LANGUAGE,
            "-o",
            destination.toString(),
            "-w",
            "%{http_code} %{content_type}",
            url));
    try {
      Files.createDirectories(destination.toAbsolutePath().getParent());
      Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(readTimeout.toSeconds() + 30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new TransientFailure("curl-impersonate não terminou: " + url);
      }
      if (process.exitValue() != 0)
        throw new TransientFailure("curl-impersonate falhou (" + process.exitValue() + "): " + err);
      String[] parts = out.strip().split(" ", 2);
      int status = Integer.parseInt(parts[0]);
      try {
        check(url, status);
      } catch (RuntimeException e) {
        Files.deleteIfExists(destination);
        throw e;
      }
      return new DownloadResult(status, parts.length > 1 && !parts[1].isBlank() ? parts[1] : null);
    } catch (IOException e) {
      throw new TransientFailure("erro executando curl-impersonate: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException("download interrompido: " + url, e);
    }
  }
}
