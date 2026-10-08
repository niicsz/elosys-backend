package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.LegacyImportPort;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.provenance.ManifestMismatch;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MaintenanceJobs {
  private MaintenanceJobs() {}

  public static final class ImportSqlite implements Job {
    private final LegacyImportPort importer;
    private final ProvenanceService provenance;

    public ImportSqlite(LegacyImportPort importer, ProvenanceService provenance) {
      this.importer = importer;
      this.provenance = provenance;
    }

    @Override
    public String name() {
      return "import-sqlite";
    }

    @Override
    public String description() {
      return "importa o elosys.db (SQLite) do projeto original para o Postgres, preservando ids";
    }

    @Override
    public Map<String, Object> run(JobParameters p) {
      String path = p.string("path", null);
      if (path == null) throw new BusinessException("informe --path=<arquivo elosys.db>");
      Map<String, Object> report = importer.importSqlite(Path.of(path), p.flag("truncate"));
      provenance.writeManifest();
      return report;
    }
  }

  public static final class Manifest implements Job {
    private final ProvenanceService provenance;

    public Manifest(ProvenanceService provenance) {
      this.provenance = provenance;
    }

    @Override
    public String name() {
      return "manifest";
    }

    @Override
    public String description() {
      return "escreve o manifest.json (fontes + hashes)";
    }

    @Override
    public boolean writesData() {
      return false;
    }

    @Override
    public Map<String, Object> run(JobParameters p) {
      return Map.of("manifest", provenance.writeManifest().toString());
    }
  }

  public static final class Verify implements Job {
    private final ProvenanceService provenance;

    public Verify(ProvenanceService provenance) {
      this.provenance = provenance;
    }

    @Override
    public String name() {
      return "verify";
    }

    @Override
    public String description() {
      return "rebaixa as fontes e confere os hashes contra o build";
    }

    @Override
    public boolean writesData() {
      return false;
    }

    @Override
    public Map<String, Object> run(JobParameters p) {
      List<ManifestMismatch> mismatches = provenance.verify();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("reproducible", mismatches.isEmpty());
      report.put("mismatches", mismatches);
      return report;
    }
  }
}
