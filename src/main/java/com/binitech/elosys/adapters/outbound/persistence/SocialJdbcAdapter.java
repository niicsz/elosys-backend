package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.SocialRepositoryPort;
import com.binitech.elosys.domain.review.PostReview;
import com.binitech.elosys.domain.social.DeclaredHandle;
import com.binitech.elosys.domain.social.SocialAccount;
import com.binitech.elosys.domain.social.SocialPostRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SocialJdbcAdapter implements SocialRepositoryPort {
  private static final Map<String, String> SCOPES =
      Map.of(
          "federal", "(ph.office LIKE '%DEPUTADO FEDERAL%' OR ph.office LIKE '%SENADOR%')",
          "deputados", "ph.office LIKE '%DEPUTADO%'",
          "electeds", "true");

  private final JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public SocialJdbcAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<DeclaredHandle> declaredXUrls(String scope) {
    String condition = SCOPES.get(scope);
    String sql =
        condition == null
            ? "SELECT DISTINCT sm.person_id, sm.url, sm.provenance_id FROM social_media sm"
                + " WHERE sm.platform = 'x'"
            : "SELECT DISTINCT sm.person_id, sm.url, sm.provenance_id FROM social_media sm"
                + " JOIN politician_history ph ON ph.person_id = sm.person_id"
                + " WHERE sm.platform = 'x' AND "
                + condition
                + " AND ph.result LIKE 'ELEITO%'";
    return jdbc.sql(sql)
        .query(
            (rs, i) ->
                new DeclaredHandle(
                    Jdbc.getLong(rs, "person_id"),
                    rs.getString("url"),
                    Jdbc.getLong(rs, "provenance_id")))
        .list();
  }

  @Override
  public SocialAccount upsertAccount(
      String network, String handle, DeclaredHandle declared, long sourceId) {
    return jdbc.sql(
            """
            INSERT INTO social_account (person_id, network, handle, handle_declared,
                declared_provenance_id, source_id, status, first_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
            ON CONFLICT (network, handle) DO UPDATE SET
              person_id = coalesce(social_account.person_id, excluded.person_id),
              declared_provenance_id = coalesce(social_account.declared_provenance_id,
                                                excluded.declared_provenance_id)
            RETURNING id, handle, person_id, status
            """)
        .params(
            declared.personId(),
            network,
            handle,
            declared.url(),
            declared.provenanceId(),
            sourceId,
            Jdbc.now())
        .query(
            (rs, i) ->
                new SocialAccount(
                    rs.getLong("id"),
                    rs.getString("handle"),
                    Jdbc.getLong(rs, "person_id"),
                    rs.getString("status")))
        .single();
  }

  @Override
  public void markAccountError(long accountId) {
    jdbc.sql("UPDATE social_account SET status = 'error' WHERE id = ?").param(accountId).update();
  }

  @Override
  public void markAccountCrawled(long accountId, String status) {
    jdbc.sql("UPDATE social_account SET status = ?, last_crawled_at = ? WHERE id = ?")
        .params(status, Jdbc.now(), accountId)
        .update();
  }

  @Override
  public boolean insertPostIfAbsent(SocialPostRow r) {
    return jdbc.sql(
                """
                INSERT INTO social_post (social_account_id, external_id, kind, lang, text,
                    in_reply_to_external, reply_to_handle, posted_at, like_count, repost_count,
                    reply_count, url, matched_terms, matched_query, apify_run_id, raw_json,
                    raw_sha256, retrieved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (social_account_id, external_id) DO NOTHING
                """)
            .params(
                r.socialAccountId(),
                r.externalId(),
                r.kind(),
                r.lang(),
                r.text().replace("\u0000", ""),
                r.inReplyToExternal(),
                r.replyToHandle(),
                r.postedAt(),
                r.likeCount(),
                r.repostCount(),
                r.replyCount(),
                r.url(),
                r.matchedTermsJson(),
                r.matchedQuery(),
                r.apifyRunId(),
                r.rawJson(),
                r.rawSha256(),
                Jdbc.now())
            .update()
        > 0;
  }

  @Override
  public Map<String, Object> crawlSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total_posts", count("SELECT count(*) FROM social_post"));
    out.put(
        "total_accounts_crawled",
        count("SELECT count(*) FROM social_account WHERE last_crawled_at IS NOT NULL"));
    return out;
  }

  @Override
  public List<PostToReview> postsToReview(
      String model, int limit, boolean onlyMatched, List<String> handles) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT p.id, p.kind, p.text, p.reply_to_handle, p.matched_terms::text AS matched_terms,
                   a.handle
            FROM social_post p JOIN social_account a ON a.id = p.social_account_id
            WHERE p.id NOT IN (SELECT social_post_id FROM social_post_review WHERE model = ?)
            """);
    List<Object> params = new ArrayList<>();
    params.add(model);
    if (onlyMatched)
      sql.append(" AND p.matched_terms IS NOT NULL AND p.matched_terms <> '[]'::jsonb");
    if (handles != null && !handles.isEmpty()) {
      sql.append(" AND a.handle = ANY(?)");
      params.add(
          handles.stream()
              .map(h -> (h.startsWith("@") ? h.substring(1) : h).toLowerCase(java.util.Locale.ROOT))
              .toArray(String[]::new));
    }
    sql.append(" ORDER BY p.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) ->
                new PostToReview(
                    rs.getLong("id"),
                    rs.getString("kind"),
                    rs.getString("text"),
                    rs.getString("reply_to_handle"),
                    rs.getString("matched_terms"),
                    rs.getString("handle")))
        .list();
  }

  @Override
  public void deleteReviews(String model) {
    jdbc.sql("DELETE FROM social_post_review WHERE model = ?").param(model).update();
  }

  @Override
  public void upsertReview(long postId, String model, PostReview r, String prompt) {
    jdbc.sql(
            """
            INSERT INTO social_post_review (social_post_id, model, reviewed_at, categories,
                severity, is_offensive, quote, explanation, prompt, raw_response, tokens_prompt,
                tokens_completion)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (social_post_id, model) DO UPDATE SET
              reviewed_at = excluded.reviewed_at, categories = excluded.categories,
              severity = excluded.severity, is_offensive = excluded.is_offensive,
              quote = excluded.quote, explanation = excluded.explanation,
              prompt = excluded.prompt, raw_response = excluded.raw_response,
              tokens_prompt = excluded.tokens_prompt,
              tokens_completion = excluded.tokens_completion
            """)
        .params(
            postId,
            model,
            Jdbc.now(),
            json.writeValueAsString(r.categories()),
            r.severity(),
            r.offensive(),
            r.quote(),
            r.explanation(),
            prompt,
            r.rawResponse(),
            r.tokensPrompt(),
            r.tokensCompletion())
        .update();
  }

  private long count(String sql) {
    Number n = jdbc.sql(sql).query(Number.class).single();
    return n == null ? 0 : n.longValue();
  }
}
