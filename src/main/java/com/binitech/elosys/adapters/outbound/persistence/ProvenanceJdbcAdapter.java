package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.ProvenanceRepositoryPort;
import com.binitech.elosys.domain.provenance.CollectionRef;
import com.binitech.elosys.domain.provenance.FileDigest;
import com.binitech.elosys.domain.provenance.ParseRequest;
import com.binitech.elosys.domain.provenance.SourceDefinition;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ProvenanceJdbcAdapter implements ProvenanceRepositoryPort {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProvenanceJdbcAdapter.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public ProvenanceJdbcAdapter(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public long sourceId(SourceDefinition s) {
    jdbc.sql(
            """
            INSERT INTO source (name, agency, type, base_url, legal_basis, notes, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO NOTHING
            """)
        .params(s.name(), s.agency(), s.type(), s.baseUrl(), s.legalBasis(), s.notes(), Jdbc.now())
        .update();
    return jdbc.sql("SELECT id FROM source WHERE name = ?")
        .param(s.name())
        .query(Long.class)
        .single();
  }

  @Override
  public CollectionRef recordCollection(
      long sourceId,
      String url,
      FileDigest payload,
      Integer httpStatus,
      String contentType,
      String collectorCommit,
      String notes) {
    Optional<CollectionRef> existing =
        jdbc.sql(
                """
                SELECT id, size_bytes FROM collection
                WHERE url = ? AND payload_sha256 = ? ORDER BY id LIMIT 1
                """)
            .params(url, payload.sha256())
            .query((rs, i) -> new CollectionRef(rs.getLong("id"), false, rs.getLong("size_bytes")))
            .optional();
    if (existing.isPresent()) return existing.get();
    long id =
        jdbc.sql(
                """
                INSERT INTO collection (source_id, url, http_status, accessed_at, payload_sha256,
                                        size_bytes, content_type, collector_commit, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(
                sourceId,
                url,
                httpStatus,
                Jdbc.now(),
                payload.sha256(),
                payload.sizeBytes(),
                contentType,
                collectorCommit,
                notes)
            .query(Long.class)
            .single();
    return new CollectionRef(id, true, payload.sizeBytes());
  }

  @Override
  public long recordFile(long collectionId, String filename, FileDigest digest) {
    jdbc.sql(
            """
            INSERT INTO collection_file (collection_id, filename, sha256, size_bytes)
            VALUES (?, ?, ?, ?) ON CONFLICT (collection_id, filename) DO NOTHING
            """)
        .params(collectionId, filename, digest.sha256(), digest.sizeBytes())
        .update();
    return jdbc.sql("SELECT id FROM collection_file WHERE collection_id = ? AND filename = ?")
        .params(collectionId, filename)
        .query(Long.class)
        .single();
  }

  @Override
  public long recordParse(ParseRequest r, String parserCommit) {
    return jdbc.sql(
            """
            INSERT INTO parse (collection_id, collection_file_id, parser_name, parser_commit,
                               parser_version, rows_extracted, rows_rejected, run_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """)
        .params(
            r.collectionId(),
            r.collectionFileId(),
            r.parserName(),
            parserCommit,
            r.parserVersion(),
            r.rowsExtracted(),
            r.rowsRejected(),
            Jdbc.now())
        .query(Long.class)
        .single();
  }

  @Override
  public void updateParseRows(long parseId, long rowsExtracted, long rowsRejected) {
    jdbc.sql("UPDATE parse SET rows_extracted = ?, rows_rejected = ? WHERE id = ?")
        .params(rowsExtracted, rowsRejected, parseId)
        .update();
  }

  @Override
  public void resetSource(String sourceName, List<String> dataTables) {
    List<String> tables = dataTables.stream().map(Jdbc::identifier).toList();
    tx.executeWithoutResult(
        status -> {
          clearTables(tables);
          Optional<Long> sourceId =
              jdbc.sql("SELECT id FROM source WHERE name = ?")
                  .param(sourceName)
                  .query(Long.class)
                  .optional();
          sourceId.ifPresent(
              sid -> {
                String ofSource = "(SELECT id FROM collection WHERE source_id = ?)";
                jdbc.sql("DELETE FROM parse WHERE collection_id IN " + ofSource)
                    .param(sid)
                    .update();
                jdbc.sql("DELETE FROM collection_file WHERE collection_id IN " + ofSource)
                    .param(sid)
                    .update();
                jdbc.sql("DELETE FROM collection WHERE source_id = ?").param(sid).update();
              });
        });
  }

  private void clearTables(List<String> tables) {
    if (tables.isEmpty()) return;
    jdbc.sql("SAVEPOINT reset_truncate").update();
    try {
      jdbc.sql("TRUNCATE " + String.join(", ", tables)).update();
      jdbc.sql("RELEASE SAVEPOINT reset_truncate").update();
    } catch (DataAccessException e) {
      jdbc.sql("ROLLBACK TO SAVEPOINT reset_truncate").update();
      LOGGER.info("TRUNCATE recusado ({}); usando DELETE", e.getMostSpecificCause().getMessage());
      for (String table : tables) jdbc.sql("DELETE FROM " + table).update();
    }
  }

  @Override
  public List<Map<String, Object>> manifestSources() {
    List<Map<String, Object>> out = new ArrayList<>();
    jdbc.sql(
            """
            SELECT c.id, s.name AS source, c.url, c.http_status, c.accessed_at,
                   c.payload_sha256, c.size_bytes
            FROM collection c JOIN source s ON s.id = c.source_id ORDER BY c.id
            """)
        .query(
            (rs, i) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("id", rs.getLong("id"));
              row.put("source", rs.getString("source"));
              row.put("url", rs.getString("url"));
              row.put("http_status", Jdbc.getInt(rs, "http_status"));
              row.put("accessed_at", iso(rs.getObject("accessed_at", OffsetDateTime.class)));
              row.put("payload_sha256", rs.getString("payload_sha256"));
              row.put("size_bytes", rs.getLong("size_bytes"));
              return row;
            })
        .list()
        .forEach(
            row -> {
              long id = (Long) row.remove("id");
              row.put(
                  "files",
                  jdbc.sql(
                          """
                          SELECT filename, sha256, size_bytes FROM collection_file
                          WHERE collection_id = ? ORDER BY filename
                          """)
                      .param(id)
                      .query(
                          (rs, i) -> {
                            Map<String, Object> f = new LinkedHashMap<>();
                            f.put("filename", rs.getString("filename"));
                            f.put("sha256", rs.getString("sha256"));
                            f.put("size_bytes", rs.getLong("size_bytes"));
                            return f;
                          })
                      .list());
              out.add(row);
            });
    return out;
  }

  @Override
  public List<CollectionToVerify> collectionsToVerify() {
    return jdbc.sql("SELECT id, url, payload_sha256 FROM collection ORDER BY id")
        .query(
            (rs, i) ->
                new CollectionToVerify(
                    rs.getLong("id"), rs.getString("url"), rs.getString("payload_sha256")))
        .list();
  }

  static String iso(OffsetDateTime t) {
    return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString();
  }
}
