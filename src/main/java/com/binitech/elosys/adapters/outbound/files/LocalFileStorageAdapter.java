package com.binitech.elosys.adapters.outbound.files;

import com.binitech.elosys.application.ports.outbound.FileStoragePort;
import com.binitech.elosys.config.ElosysProperties;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.provenance.FileDigest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorageAdapter implements FileStoragePort {
  private static final CSVFormat SEMICOLON_CSV =
      CSVFormat.Builder.create()
          .setDelimiter(';')
          .setQuote('"')
          .setRecordSeparator("\r\n")
          .setIgnoreEmptyLines(true)
          .setLenientEof(true)
          .setTrailingData(true)
          .get();

  private final Path tmpDir;
  private final Path reportsDir;

  public LocalFileStorageAdapter(ElosysProperties properties) {
    this.tmpDir = Path.of(properties.tmpDir()).toAbsolutePath();
    this.reportsDir = Path.of(properties.reportsDir()).toAbsolutePath();
  }

  @Override
  public Path tmpDir() {
    try {
      return Files.createDirectories(tmpDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean exists(Path path) {
    return Files.isRegularFile(path);
  }

  @Override
  public void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  @Override
  public FileDigest digest(Path path) {
    try (InputStream in = Files.newInputStream(path)) {
      MessageDigest sha = sha256();
      byte[] buffer = new byte[1 << 20];
      long size = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        sha.update(buffer, 0, read);
        size += read;
      }
      return new FileDigest(HexFormat.of().formatHex(sha.digest()), size);
    } catch (IOException e) {
      throw new BusinessException("não foi possível ler " + path + ": " + e.getMessage(), e);
    }
  }

  @Override
  public List<String> zipCsvMembers(Path zip) {
    try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.ISO_8859_1)) {
      List<String> names = new ArrayList<>();
      zf.stream()
          .map(ZipEntry::getName)
          .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".csv"))
          .forEach(names::add);
      return names;
    } catch (IOException e) {
      throw new BusinessException("zip inválido " + zip + ": " + e.getMessage(), e);
    }
  }

  @Override
  public ExtractedFile extract(Path zip, String member) {
    try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.ISO_8859_1)) {
      ZipEntry entry = zf.getEntry(member);
      if (entry == null) throw new BusinessException("membro " + member + " não está em " + zip);
      Path target = Files.createTempFile(tmpDir(), "extract-", ".csv");
      MessageDigest sha = sha256();
      long size;
      try (InputStream in = zf.getInputStream(entry);
          OutputStream out = new DigestOutputStream(Files.newOutputStream(target), sha)) {
        size = in.transferTo(out);
      }
      return new ExtractedFile(
          member, target, new FileDigest(HexFormat.of().formatHex(sha.digest()), size));
    } catch (IOException e) {
      throw new BusinessException("falha extraindo " + member + ": " + e.getMessage(), e);
    }
  }

  @Override
  public long forEachRecord(Path csv, Consumer<CsvRecord> consumer) {
    try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.ISO_8859_1);
        CSVParser parser = SEMICOLON_CSV.parse(reader)) {
      Iterator<CSVRecord> it = parser.iterator();
      if (!it.hasNext()) return 0;
      Map<String, Integer> header = new HashMap<>();
      CSVRecord first = it.next();
      for (int i = 0; i < first.size(); i++) header.put(first.get(i), i);
      long n = 0;
      while (it.hasNext()) {
        CSVRecord record = it.next();
        consumer.accept(new MappedRecord(header, record));
        n++;
      }
      return n;
    } catch (IOException | UncheckedIOException e) {
      throw new BusinessException("CSV ilegível " + csv + ": " + e.getMessage(), e);
    }
  }

  @Override
  public long forEachRow(Path csv, Consumer<List<String>> consumer) {
    try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.ISO_8859_1);
        CSVParser parser = SEMICOLON_CSV.parse(reader)) {
      Iterator<CSVRecord> it = parser.iterator();
      if (it.hasNext()) it.next();
      long n = 0;
      while (it.hasNext()) {
        consumer.accept(it.next().toList());
        n++;
      }
      return n;
    } catch (IOException | UncheckedIOException e) {
      throw new BusinessException("CSV ilegível " + csv + ": " + e.getMessage(), e);
    }
  }

  @Override
  public String sha256(String text) {
    return HexFormat.of().formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Override
  public Path writeReport(String fileName, String json) {
    try {
      Files.createDirectories(reportsDir);
      Path target = reportsDir.resolve(fileName);
      Files.writeString(target, json + "\n", StandardCharsets.UTF_8);
      return target;
    } catch (IOException e) {
      throw new BusinessException("não foi possível gravar " + fileName + ": " + e.getMessage(), e);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record MappedRecord(Map<String, Integer> header, CSVRecord record) implements CsvRecord {
    @Override
    public String get(String column) {
      Integer index = header.get(column);
      if (index == null || index >= record.size()) return null;
      return record.get(index);
    }

    @Override
    public boolean has(String column) {
      return header.containsKey(column);
    }
  }
}
