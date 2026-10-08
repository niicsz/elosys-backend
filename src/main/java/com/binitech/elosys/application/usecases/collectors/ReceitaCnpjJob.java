package com.binitech.elosys.application.usecases.collectors;

import static com.binitech.elosys.domain.SourceValues.brlToCents;
import static com.binitech.elosys.domain.SourceValues.digitsOnly;
import static com.binitech.elosys.domain.SourceValues.isoDate;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.domain.company.CompanyPartnerRow;
import com.binitech.elosys.domain.company.CompanyRegistryRow;
import com.binitech.elosys.domain.company.CompanyTarget;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.SourceNotFoundException;
import com.binitech.elosys.domain.provenance.CollectionRef;
import com.binitech.elosys.domain.provenance.DownloadResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReceitaCnpjJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReceitaCnpjJob.class);

  static final String PARSER_NAME = "receita.cnpj";
  static final String PARSER_VERSION = "1.0";
  static final String URL_TEMPLATE = "https://brasilapi.com.br/api/cnpj/v1/%s";
  static final int DEFAULT_LIMIT = 500;

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final SourceDownloadPort downloads;
  private final CompanyRepositoryPort companies;

  public ReceitaCnpjJob(
      ProvenanceService provenance,
      CollectorSupport support,
      SourceDownloadPort downloads,
      CompanyRepositoryPort companies) {
    this.provenance = provenance;
    this.support = support;
    this.downloads = downloads;
    this.companies = companies;
  }

  @Override
  public String name() {
    return "receita-cnpj";
  }

  @Override
  public String description() {
    return "busca incremental de CNPJ na BrasilAPI -> company_registry/company_partner"
        + " [--limit=500] [--order=money|id] [--include-campaign] [--cnpjs=...]";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    int limit = p.integer("limit", DEFAULT_LIMIT);
    String order = p.string("order", "money");
    if (!order.equals("money") && !order.equals("id"))
      throw new BusinessException("--order deve ser money ou id");
    boolean includeCampaign = p.flag("include-campaign");
    List<String> cnpjs =
        p.list("cnpjs").stream().map(c -> digitsOnly(c)).filter(Objects::nonNull).toList();
    long sourceId = provenance.sourceId(Sources.BRASILAPI_CNPJ);

    List<CompanyTarget> targets =
        !cnpjs.isEmpty()
            ? companies.findByCnpjs(cnpjs)
            : order.equals("money")
                ? companies.missingRegistryByMoney(limit, includeCampaign)
                : companies.missingRegistryById(limit, includeCampaign);
    LOGGER.info("{} empresas para buscar (limit={}, order={})", targets.size(), limit, order);

    Map<String, Object> report = new LinkedHashMap<>();
    int fetched = 0, notFound = 0, errors = 0;
    Progress rc = new Progress(LOGGER, "consultas CNPJ", 50);
    for (CompanyTarget t : targets) {
      rc.tick();
      switch (fetchOne(sourceId, t)) {
        case "fetched" -> fetched++;
        case "not_found" -> notFound++;
        default -> errors++;
      }
    }
    rc.done();
    report.put("fetched", fetched);
    report.put("not_found", notFound);
    report.put("errors", errors);
    report.put("total_cached", companies.countRegistries());
    report.put("remaining", companies.countMissingRegistry());
    report.put("total_partners", companies.countPartners());
    LOGGER.info("pronto: {}", report);
    return report;
  }

  @SuppressWarnings("unchecked")
  private String fetchOne(long sourceId, CompanyTarget t) {
    String url = URL_TEMPLATE.formatted(t.cnpj());
    Path path = support.tmpFile("brasilapi_cnpj_" + t.cnpj() + ".json");
    try {
      DownloadResult result;
      try {
        result = downloads.download(url, path, SourceProfile.LOOKUP);
      } catch (SourceNotFoundException e) {
        return "not_found";
      } catch (BusinessException e) {
        LOGGER.warn("  {}: {}", t.cnpj(), e.getMessage());
        return "error";
      }
      Object parsed;
      try {
        parsed = support.readJson(path);
      } catch (BusinessException e) {
        LOGGER.warn("  {}: resposta inválida ({})", t.cnpj(), e.getMessage());
        return "error";
      }
      if (!(parsed instanceof Map<?, ?> raw)) return "error";
      Map<String, Object> payload = (Map<String, Object>) raw;
      if ("NOT_FOUND".equals(payload.get("type"))) return "not_found";

      CollectionRef collection =
          provenance.recordCollection(
              sourceId,
              url,
              path,
              result.httpStatus(),
              result.contentType(),
              "BrasilAPI CNPJ lookup for " + t.cnpj() + ".");
      long parseId = provenance.recordParse(collection.id(), PARSER_NAME, PARSER_VERSION, 1);
      String legalName = str(payload.get("razao_social"));
      String tradeName = str(payload.get("nome_fantasia"));
      Object capital = payload.get("capital_social");
      companies.upsertRegistry(
          new CompanyRegistryRow(
              t.id(),
              t.cnpj(),
              legalName,
              tradeName == null || tradeName.isEmpty() ? null : tradeName,
              isoDate(str(payload.get("data_inicio_atividade"))),
              str(payload.get("descricao_situacao_cadastral")),
              isoDate(str(payload.get("data_situacao_cadastral"))),
              str(payload.get("natureza_juridica")),
              str(payload.get("cnae_fiscal_descricao")),
              brlToCents(capital == null || Boolean.FALSE.equals(capital) ? "" : number(capital)),
              str(payload.get("porte")),
              str(payload.get("municipio")),
              str(payload.get("uf")),
              parseId));
      if (legalName != null) companies.fillLegalNameIfMissing(t.id(), legalName);

      Object qsa = payload.get("qsa");
      if (qsa instanceof List<?> partners) {
        companies.insertPartnersIgnoringDuplicates(
            partners.stream()
                .filter(o -> o instanceof Map<?, ?>)
                .map(o -> (Map<String, Object>) o)
                .filter(
                    m -> str(m.get("nome_socio")) != null && !str(m.get("nome_socio")).isEmpty())
                .map(
                    m ->
                        new CompanyPartnerRow(
                            t.id(),
                            t.cnpj(),
                            str(m.get("nome_socio")),
                            str(m.get("cnpj_cpf_do_socio")),
                            str(m.get("qualificacao_socio")),
                            isoDate(str(m.get("data_entrada_sociedade"))),
                            parseId))
                .toList());
      }
      return "fetched";
    } finally {
      support.delete(path);
    }
  }

  private static String str(Object v) {
    return v == null ? null : v instanceof Number n ? number(n) : v.toString();
  }

  private static String number(Object v) {
    if (v instanceof Double d)
      return d == Math.rint(d) && !d.isInfinite() ? String.valueOf(d) : d.toString();
    return v.toString();
  }
}
