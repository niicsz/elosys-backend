package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.TseExtrasRepositoryPort;
import com.binitech.elosys.domain.assets.CandidatePhotoRow;
import com.binitech.elosys.domain.assets.DeclaredAssetRow;
import com.binitech.elosys.domain.finance.SocialMediaRow;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class TseExtrasJdbcAdapter implements TseExtrasRepositoryPort {
  private static final List<String> SOCIAL_COLUMNS =
      List.of(
          "person_id",
          "tse_candidacy_id",
          "year",
          "state",
          "platform",
          "url",
          "order_in_source",
          "provenance_id",
          "collected_at");
  private static final List<String> ASSET_COLUMNS =
      List.of(
          "person_id",
          "history_id",
          "tse_candidacy_id",
          "year",
          "state",
          "asset_order",
          "asset_type",
          "description",
          "value_cents",
          "source_updated_at",
          "provenance_id",
          "collected_at");
  private static final String NO_PHOTO_YET =
      "NOT EXISTS (SELECT 1 FROM candidate_photo cp WHERE cp.person_id = p.id)";

  private final JdbcClient jdbc;
  private final JdbcTemplate template;
  private final CopyWriter copy;

  public TseExtrasJdbcAdapter(JdbcClient jdbc, JdbcTemplate template, CopyWriter copy) {
    this.jdbc = jdbc;
    this.template = template;
    this.copy = copy;
  }

  @Override
  public int insertSocialMedia(List<SocialMediaRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "social_media",
        SOCIAL_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.personId(),
                      r.tseCandidacyId(),
                      r.year(),
                      r.state(),
                      r.platform(),
                      r.url(),
                      r.orderInSource(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public Map<String, Object> socialMediaSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("urls", count("SELECT count(*) FROM social_media"));
    out.put("candidacies", count("SELECT count(DISTINCT tse_candidacy_id) FROM social_media"));
    out.put(
        "linked_to_person", count("SELECT count(*) FROM social_media WHERE person_id IS NOT NULL"));
    Map<String, Long> byPlatform = new LinkedHashMap<>();
    jdbc.sql("SELECT platform, count(*) FROM social_media GROUP BY platform ORDER BY 2 DESC")
        .query(
            rs -> {
              byPlatform.put(rs.getString(1), rs.getLong(2));
            });
    out.put("by_platform", byPlatform);
    return out;
  }

  @Override
  public int insertDeclaredAssets(List<DeclaredAssetRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "declared_assets",
        ASSET_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.personId(),
                      r.historyId(),
                      r.tseCandidacyId(),
                      r.year(),
                      r.state(),
                      r.assetOrder(),
                      r.assetType(),
                      r.description(),
                      r.valueCents(),
                      r.sourceUpdatedAt(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public Map<String, Object> declaredAssetsSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("assets", count("SELECT count(*) FROM declared_assets"));
    out.put("candidacies", count("SELECT count(DISTINCT tse_candidacy_id) FROM declared_assets"));
    out.put(
        "linked_to_person",
        count("SELECT count(*) FROM declared_assets WHERE person_id IS NOT NULL"));
    out.put(
        "total_value_cents", count("SELECT coalesce(sum(value_cents), 0) FROM declared_assets"));
    return out;
  }

  @Override
  public List<PersonCpfTarget> photoTargets(int limit) {
    return jdbc.sql(
            "SELECT p.id, p.cpf, max(ph.year) AS latest_year FROM people p"
                + " JOIN politician_history ph ON ph.person_id = p.id"
                + " WHERE p.cpf IS NOT NULL AND "
                + NO_PHOTO_YET
                + " GROUP BY p.id ORDER BY latest_year DESC, p.id LIMIT ?")
        .param(limit)
        .query((rs, i) -> new PersonCpfTarget(rs.getLong("id"), rs.getString("cpf")))
        .list();
  }

  @Override
  public List<PersonCpfTarget> photoTargetsForYears(Collection<Integer> years, int limit) {
    return jdbc.sql(
            "SELECT DISTINCT p.id, p.cpf FROM people p"
                + " JOIN politician_history ph ON ph.person_id = p.id AND ph.year = ANY(?)"
                + " WHERE p.cpf IS NOT NULL AND "
                + NO_PHOTO_YET
                + " LIMIT ?")
        .params(years.toArray(Integer[]::new), limit)
        .query((rs, i) -> new PersonCpfTarget(rs.getLong("id"), rs.getString("cpf")))
        .list();
  }

  @Override
  public List<PersonCpfTarget> photoTargetsForPeople(Collection<Long> personIds) {
    return jdbc.sql("SELECT id, cpf FROM people WHERE id = ANY(?) AND cpf IS NOT NULL")
        .param(personIds.toArray(Long[]::new))
        .query((rs, i) -> new PersonCpfTarget(rs.getLong("id"), rs.getString("cpf")))
        .list();
  }

  @Override
  public void insertPhotosIgnoringDuplicates(List<CandidatePhotoRow> rows) {
    OffsetDateTime now = Jdbc.now();
    template.batchUpdate(
        """
        INSERT INTO candidate_photo (person_id, history_id, tse_candidacy_id, year, photo_url,
                                     provenance_id, collected_at)
        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """,
        rows,
        500,
        (ps, r) -> {
          ps.setLong(1, r.personId());
          Jdbc.setLong(ps, 2, r.historyId());
          ps.setString(3, r.tseCandidacyId());
          Jdbc.setInt(ps, 4, r.year());
          ps.setString(5, r.photoUrl());
          ps.setLong(6, r.provenanceId());
          ps.setObject(7, now);
        });
  }

  @Override
  public Map<String, Object> photoSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total_cached", count("SELECT count(DISTINCT person_id) FROM candidate_photo"));
    out.put(
        "remaining",
        count(
            "SELECT count(DISTINCT p.id) FROM people p"
                + " JOIN politician_history ph ON ph.person_id = p.id"
                + " WHERE p.cpf IS NOT NULL AND "
                + NO_PHOTO_YET));
    out.put("total_urls", count("SELECT count(*) FROM candidate_photo"));
    return out;
  }

  private long count(String sql) {
    Number n = jdbc.sql(sql).query(Number.class).single();
    return n == null ? 0 : n.longValue();
  }
}
