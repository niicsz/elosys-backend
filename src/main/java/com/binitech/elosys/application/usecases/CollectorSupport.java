package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.outbound.FileStoragePort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.CsvRecord;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class CollectorSupport {
  private final FileStoragePort files;
  private final JsonPort json;

  public CollectorSupport(FileStoragePort files, JsonPort json) {
    this.files = files;
    this.json = json;
  }

  public long forEachRecord(Path csv, Consumer<CsvRecord> consumer) {
    return files.forEachRecord(csv, consumer);
  }

  public long forEachRow(Path csv, Consumer<List<String>> consumer) {
    return files.forEachRow(csv, consumer);
  }

  public void delete(Path path) {
    files.deleteQuietly(path);
  }

  public Path tmpFile(String name) {
    return files.tmpDir().resolve(name);
  }

  public Object readJson(Path path) {
    return json.readFile(path);
  }

  public String canonicalJson(Object value) {
    return json.canonical(value);
  }

  public String toJson(Object value) {
    return json.write(value);
  }

  public Object parseJson(String text) {
    return json.parse(text);
  }

  public String sha256(String text) {
    return files.sha256(text);
  }
}
