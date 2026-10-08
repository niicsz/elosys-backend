package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.provenance.FileDigest;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public interface FileStoragePort {
  Path tmpDir();

  boolean exists(Path path);

  void deleteQuietly(Path path);

  FileDigest digest(Path path);

  List<String> zipCsvMembers(Path zip);

  ExtractedFile extract(Path zip, String member);

  long forEachRecord(Path csv, Consumer<CsvRecord> consumer);

  long forEachRow(Path csv, Consumer<List<String>> consumer);

  String sha256(String text);

  Path writeReport(String fileName, String json);

  record ExtractedFile(String name, Path path, FileDigest digest) {}

  interface CsvRecord {
    String get(String column);

    boolean has(String column);
  }
}
