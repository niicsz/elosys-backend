package com.binitech.elosys.application.usecases.collectors;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.LexiconPort;
import com.binitech.elosys.application.ports.outbound.ProvenanceRepositoryPort;
import com.binitech.elosys.application.ports.outbound.ScraperPort;
import com.binitech.elosys.application.ports.outbound.ScraperPort.ScrapeRun;
import com.binitech.elosys.application.ports.outbound.SocialRepositoryPort;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.social.DeclaredHandle;
import com.binitech.elosys.domain.social.Lexicon;
import com.binitech.elosys.domain.social.SocialAccount;
import com.binitech.elosys.domain.social.SocialPostRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocialXJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(SocialXJob.class);

  static final String NETWORK = "x";
  static final String DEFAULT_SINCE = "2019-01-01";
  static final int DEFAULT_MAX_ITEMS = 120;
  static final String DEFAULT_MIN_WEIGHT = "baixa";
  static final int DEFAULT_BATCH_SIZE = 10;
  static final int DEFAULT_WORKERS = 4;
  static final Set<String> SCOPES = Set.of("federal", "deputados", "electeds", "all");

  private static final Pattern HANDLE =
      Pattern.compile(
          "(?:twitter\\.com(?:\\.br)?|x\\.com)/@?([A-Za-z0-9_]{1,15})", Pattern.CASE_INSENSITIVE);
  private static final Set<String> RESERVED =
      Set.of(
          "home",
          "share",
          "intent",
          "i",
          "hashtag",
          "search",
          "explore",
          "notifications",
          "messages",
          "settings",
          "compose",
          "login",
          "signup",
          "about",
          "tos",
          "privacy");

  private final SocialRepositoryPort social;
  private final ScraperPort scraper;
  private final LexiconPort lexicons;
  private final ProvenanceRepositoryPort provenance;
  private final CollectorSupport support;

  public SocialXJob(
      SocialRepositoryPort social,
      ScraperPort scraper,
      LexiconPort lexicons,
      ProvenanceRepositoryPort provenance,
      CollectorSupport support) {
    this.social = social;
    this.scraper = scraper;
    this.lexicons = lexicons;
    this.provenance = provenance;
    this.support = support;
  }

  @Override
  public String name() {
    return "social-x";
  }

  @Override
  public String description() {
    return "coleta posts/replies do X de contas declaradas ao TSE (Apify; requer APIFY_TOKEN)"
        + " [--scope=federal|deputados|electeds|all] [--handles=...] [--limit=N]"
        + " [--min-weight=baixa|media|alta] [--max-items=120] [--since=2019-01-01]"
        + " [--batch-size=10] [--workers=4] [--refresh]";
  }

  public static String normalizeHandle(String raw) {
    if (raw == null || raw.isBlank()) return null;
    Matcher m = HANDLE.matcher(raw.strip());
    if (!m.find()) return null;
    String h = m.group(1).toLowerCase(Locale.ROOT);
    if (RESERVED.contains(h) || h.chars().allMatch(Character::isDigit)) return null;
    return h;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    String scope = p.string("scope", "federal");
    if (!SCOPES.contains(scope)) throw new BusinessException("scope inválido: " + scope);
    if (!scraper.configured()) throw new BusinessException("APIFY_TOKEN não definida no ambiente");
    String minWeight = p.string("min-weight", DEFAULT_MIN_WEIGHT);
    if (!Lexicon.WEIGHT_RANK.containsKey(minWeight))
      throw new BusinessException("--min-weight deve ser baixa, media ou alta");
    int maxItems = p.integer("max-items", DEFAULT_MAX_ITEMS);
    String since = p.string("since", DEFAULT_SINCE);
    int batchSize = Math.max(1, p.integer("batch-size", DEFAULT_BATCH_SIZE));
    int workers = Math.max(1, p.integer("workers", DEFAULT_WORKERS));
    Integer limit = p.optionalInteger("limit");
    long sourceId = provenance.sourceId(Sources.X_POSTS);
    Lexicon lexicon = lexicons.lexicon();

    List<SocialAccount> targets = resolveTargets(sourceId, scope, p.list("handles"));
    if (!p.flag("refresh"))
      targets =
          targets.stream()
              .filter(t -> !"active".equals(t.status()) && !"empty".equals(t.status()))
              .toList();
    if (limit != null && limit > 0 && targets.size() > limit) targets = targets.subList(0, limit);

    List<List<SocialAccount>> batches = new ArrayList<>();
    for (int i = 0; i < targets.size(); i += batchSize)
      batches.add(targets.subList(i, Math.min(targets.size(), i + batchSize)));
    LOGGER.info(
        "{} contas em {} lote(s) de até {} (scope={}, min_weight={}, max_items={}, since={},"
            + " workers={})",
        targets.size(),
        batches.size(),
        batchSize,
        scope,
        minWeight,
        maxItems,
        since,
        workers);

    Map<String, Integer> byStatus = new TreeMap<>();
    long postsNew = 0;
    Progress rc = new Progress(LOGGER, "lotes concluídos", 1);
    try (ExecutorService pool =
        Executors.newFixedThreadPool(workers, Thread.ofVirtual().factory())) {
      CompletionService<BatchResult> done = new ExecutorCompletionService<>(pool);
      Map<Future<BatchResult>, List<SocialAccount>> submitted = new HashMap<>();
      for (List<SocialAccount> batch : batches) {
        submitted.put(
            done.submit(() -> fetchBatch(batch, lexicon, minWeight, maxItems, since)), batch);
      }
      for (int i = 0; i < batches.size(); i++) {
        Future<BatchResult> future;
        try {
          future = done.take();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new BusinessException("interrompido", e);
        }
        rc.tick();
        List<SocialAccount> batch = submitted.get(future);
        BatchResult result;
        try {
          result = future.get();
        } catch (ExecutionException | InterruptedException e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          LOGGER.warn(
              "  lote ({} contas, @{}...): {}",
              batch.size(),
              batch.getFirst().handle(),
              cause.getMessage());
          for (SocialAccount t : batch) {
            social.markAccountError(t.id());
            byStatus.merge("error", 1, Integer::sum);
          }
          continue;
        }
        postsNew += storeBatch(batch, result, lexicon, byStatus);
      }
    }
    rc.done();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("accounts_crawled_this_run", targets.size());
    report.put("by_status", byStatus);
    report.put("posts_new_this_run", postsNew);
    report.putAll(social.crawlSummary());
    LOGGER.info("pronto: {}", report);
    return report;
  }

  private List<SocialAccount> resolveTargets(long sourceId, String scope, List<String> handles) {
    Map<String, DeclaredHandle> picked = new LinkedHashMap<>();
    if (!handles.isEmpty()) {
      Set<String> wanted = new LinkedHashSet<>();
      for (String h : handles)
        wanted.add((h.startsWith("@") ? h.substring(1) : h).toLowerCase(Locale.ROOT));
      for (DeclaredHandle d : social.declaredXUrls("all")) {
        String h = normalizeHandle(d.url());
        if (h != null && wanted.contains(h)) picked.putIfAbsent(h, d);
      }
      for (String h : wanted) picked.putIfAbsent(h, new DeclaredHandle(null, null, null));
    } else {
      for (DeclaredHandle d : social.declaredXUrls(scope)) {
        String h = normalizeHandle(d.url());
        if (h != null) picked.putIfAbsent(h, d);
      }
    }
    return picked.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> social.upsertAccount(NETWORK, e.getKey(), e.getValue(), sourceId))
        .toList();
  }

  private record BatchResult(String runId, List<Map<String, Object>> items, List<String> queries) {}

  private BatchResult fetchBatch(
      List<SocialAccount> batch, Lexicon lexicon, String minWeight, int maxItems, String since) {
    List<String> queries = new ArrayList<>();
    for (SocialAccount t : batch)
      queries.addAll(
          lexicon.buildQueries(t.handle(), minWeight, "since:" + since + " -filter:retweets"));
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("searchTerms", queries);
    input.put("maxItems", maxItems * batch.size());
    input.put("sort", "Latest");
    ScrapeRun run = scraper.run(input, 2400);
    LOGGER.info(
        "  lote @{}..+{}: {} itens brutos",
        batch.getFirst().handle(),
        batch.size() - 1,
        run.items().size());
    return new BatchResult(run.runId(), run.items(), queries);
  }

  private long storeBatch(
      List<SocialAccount> batch,
      BatchResult result,
      Lexicon lexicon,
      Map<String, Integer> byStatus) {
    Map<String, List<Map<String, Object>>> perHandle = new HashMap<>();
    for (SocialAccount t : batch) perHandle.put(t.handle(), new ArrayList<>());
    for (Map<String, Object> item : result.items()) {
      String id = String.valueOf(item.get("id"));
      if (id.equals("-1") || id.equals("null") || id.isEmpty()) continue;
      String author =
          item.get("author") instanceof Map<?, ?> a && a.get("userName") != null
              ? a.get("userName").toString().toLowerCase(Locale.ROOT)
              : "";
      List<Map<String, Object>> list = perHandle.get(author);
      if (list != null) list.add(item);
    }
    long created = 0;
    for (SocialAccount t : batch) {
      List<Map<String, Object>> got = perHandle.get(t.handle());
      for (Map<String, Object> item : got) {
        String query = null;
        if (item.get("searchTermIndex") instanceof Number n) {
          int idx = n.intValue();
          if (idx >= 0 && idx < result.queries().size()) query = result.queries().get(idx);
        }
        SocialPostRow row = toPost(t, result.runId(), item, lexicon, query);
        if (row != null && social.insertPostIfAbsent(row)) created++;
      }
      String status = got.isEmpty() ? "empty" : "active";
      social.markAccountCrawled(t.id(), status);
      byStatus.merge(status, 1, Integer::sum);
    }
    return created;
  }

  private static Object first(Map<String, Object> item, String... keys) {
    for (String k : keys) {
      Object v = item.get(k);
      if (v == null) continue;
      if (v instanceof String s && s.isEmpty()) continue;
      if (v instanceof Collection<?> c && c.isEmpty()) continue;
      return v;
    }
    return null;
  }

  private static boolean truthy(Object v) {
    if (v == null) return false;
    if (v instanceof Boolean b) return b;
    if (v instanceof Number n) return n.doubleValue() != 0;
    if (v instanceof String s) return !s.isEmpty();
    if (v instanceof Collection<?> c) return !c.isEmpty();
    if (v instanceof Map<?, ?> m) return !m.isEmpty();
    return true;
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private static Long count(Object v) {
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s && !s.isEmpty() && s.chars().allMatch(Character::isDigit))
      return Long.parseLong(s);
    return null;
  }

  private SocialPostRow toPost(
      SocialAccount account,
      String runId,
      Map<String, Object> item,
      Lexicon lexicon,
      String query) {
    Object externalId = first(item, "id", "id_str", "tweetId", "rest_id");
    Object textValue = first(item, "text", "full_text", "fullText", "rawContent", "content");
    String text = textValue == null ? "" : textValue.toString();
    if (externalId == null || text.isEmpty()) return null;
    String id =
        externalId instanceof Number n ? String.valueOf(n.longValue()) : externalId.toString();

    boolean isReply = truthy(first(item, "isReply", "is_reply")) || item.get("inReplyToId") != null;
    boolean isQuote = truthy(first(item, "isQuote", "is_quote", "quoted_tweet", "quotedTweet"));
    String kind = isReply ? "reply" : isQuote ? "quote" : "post";
    String raw = support.canonicalJson(item);
    Object url = first(item, "url", "twitterUrl", "tweetUrl");
    return new SocialPostRow(
        account.id(),
        id,
        kind,
        str(first(item, "lang", "language")),
        text,
        str(first(item, "inReplyToId", "in_reply_to_status_id_str", "conversationId")),
        str(first(item, "inReplyToUsername", "in_reply_to_screen_name")),
        str(first(item, "createdAt", "created_at", "date", "timestamp")),
        count(first(item, "likeCount", "favorite_count", "likes")),
        count(first(item, "retweetCount", "retweet_count", "reposts")),
        count(first(item, "replyCount", "reply_count", "replies")),
        url != null ? url.toString() : "https://x.com/" + account.handle() + "/status/" + id,
        support.toJson(lexicon.matches(text)),
        query,
        runId,
        raw,
        support.sha256(raw));
  }
}
