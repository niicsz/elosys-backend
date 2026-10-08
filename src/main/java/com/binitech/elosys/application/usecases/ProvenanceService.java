package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.outbound.BuildInfoPort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.ExtractedFile;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.ProvenanceRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.provenance.CollectionRef;
import com.binitech.elosys.domain.provenance.DownloadResult;
import com.binitech.elosys.domain.provenance.FileDigest;
import com.binitech.elosys.domain.provenance.ManifestMismatch;
import com.binitech.elosys.domain.provenance.ParseRequest;
import com.binitech.elosys.domain.provenance.SourceDefinition;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProvenanceService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProvenanceService.class);

  private final ProvenanceRepositoryPort repository;
  private final SourceDownloadPort downloads;
  private final FileStoragePort files;
  private final BuildInfoPort build;
  private final JsonPort json;

  public ProvenanceService(
      ProvenanceRepositoryPort repository,
      SourceDownloadPort downloads,
      FileStoragePort files,
      BuildInfoPort build,
      JsonPort json) {
    this.repository = repository;
    this.downloads = downloads;
    this.files = files;
    this.build = build;
    this.json = json;
  }

  public long sourceId(SourceDefinition source) {
    return repository.sourceId(source);
  }

  public void reset(SourceDefinition source, List<String> dataTables) {
    repository.resetSource(source.name(), dataTables);
  }

  public Path tmpFile(String name) {
    return files.tmpDir().resolve(name);
  }

  public AcquiredFile acquire(
      long sourceId,
      String url,
      String localName,
      SourceProfile profile,
      boolean allowLocal,
      String downloadedNotes,
      String localNotes) {
    Path path = tmpFile(localName);
    DownloadResult result;
    boolean keep;
    String notes;
    if (allowLocal && files.exists(path)) {
      LOGGER.info("  usando arquivo local {}", path);
      result = DownloadResult.providedLocally();
      keep = true;
      notes = localNotes;
    } else {
      LOGGER.info("  download {}", url);
      result = downloads.download(url, path, profile);
      keep = false;
      notes = downloadedNotes;
    }
    CollectionRef collection =
        recordCollection(sourceId, url, path, result.httpStatus(), result.contentType(), notes);
    LOGGER.info(
        "  {}  collection {}  ({})",
        collection.isNew() ? "nova" : "já registrada",
        collection.id(),
        Progress.humanBytes(collection.sizeBytes()));
    return new AcquiredFile(path, collection, keep);
  }

  public CollectionRef recordCollection(
      long sourceId, String url, Path file, Integer status, String contentType, String notes) {
    FileDigest digest = files.digest(file);
    return repository.recordCollection(
        sourceId, url, digest, status, contentType, build.gitCommit(), notes);
  }

  public void release(AcquiredFile file) {
    if (!file.keep()) files.deleteQuietly(file.path());
  }

  public List<String> csvMembers(Path zip) {
    return files.zipCsvMembers(zip);
  }

  public ExtractedFile extract(Path zip, String member) {
    return files.extract(zip, member);
  }

  public long startParse(
      CollectionRef collection, ExtractedFile file, String parserName, String parserVersion) {
    long fileId = repository.recordFile(collection.id(), file.name(), file.digest());
    return repository.recordParse(
        new ParseRequest(collection.id(), fileId, parserName, parserVersion, 0, 0),
        build.gitCommit());
  }

  public long recordParse(
      long collectionId, String parserName, String parserVersion, long rowsExtracted) {
    return repository.recordParse(
        new ParseRequest(collectionId, null, parserName, parserVersion, rowsExtracted, 0),
        build.gitCommit());
  }

  public void finishParse(long parseId, long rowsExtracted, long rowsRejected) {
    repository.updateParseRows(parseId, rowsExtracted, rowsRejected);
  }

  public String gitCommit() {
    return build.gitCommit();
  }

  public long sizeOf(CollectionRef collection) {
    return collection.sizeBytes();
  }

  public Path writeManifest() {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("generated_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    manifest.put("collector_commit", build.gitCommit());
    manifest.put("sources", repository.manifestSources());
    return files.writeReport("manifest.json", json.writePretty(manifest));
  }

  public List<ManifestMismatch> verify() {
    List<ManifestMismatch> mismatches = new ArrayList<>();
    for (var c : repository.collectionsToVerify()) {
      Path tmp = tmpFile("verify_" + c.id() + ".tmp");
      try {
        downloads.download(c.url(), tmp, SourceProfile.BULK);
        String got = files.digest(tmp).sha256();
        if (!got.equals(c.payloadSha256()))
          mismatches.add(new ManifestMismatch(c.id(), c.url(), c.payloadSha256(), got));
      } catch (BusinessException e) {
        mismatches.add(
            new ManifestMismatch(c.id(), c.url(), c.payloadSha256(), "erro: " + e.getMessage()));
      } finally {
        files.deleteQuietly(tmp);
      }
    }
    return mismatches;
  }

  public record AcquiredFile(Path path, CollectionRef collection, boolean keep) {}
}
