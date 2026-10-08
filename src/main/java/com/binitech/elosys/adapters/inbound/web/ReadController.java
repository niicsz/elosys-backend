package com.binitech.elosys.adapters.inbound.web;

import com.binitech.elosys.application.ports.inbound.ReadModelQueryPort;
import com.binitech.elosys.application.ports.inbound.ReadModelQueryPort.FinanceRequest;
import com.binitech.elosys.domain.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReadController {
  private final ReadModelQueryPort queries;

  public ReadController(ReadModelQueryPort queries) {
    this.queries = queries;
  }

  @GetMapping("/search")
  public Map<String, Object> search(@RequestParam(defaultValue = "") String q) {
    return queries.search(q);
  }

  @GetMapping("/home")
  public Map<String, Object> home(@RequestParam(required = false) Integer ano) {
    return queries.home(ano);
  }

  @GetMapping("/meta")
  public Map<String, Object> meta() {
    return queries.meta();
  }

  @GetMapping("/politicos/{id}")
  public Map<String, Object> politician(
      @PathVariable long id, @RequestParam(required = false) Integer ano) {
    Map<String, Object> doc = queries.politician(id, ano);
    if (doc == null) throw new NotFoundException("político " + id + " não encontrado");
    return doc;
  }

  @GetMapping("/politicos/{id}/despesas-categoria")
  public Map<String, Object> politicianCategoryExpenses(
      @PathVariable long id,
      @RequestParam(required = false) String categoria,
      @RequestParam(required = false) Integer ano) {
    return Map.of("rows", queries.politicianCategoryExpenses(id, categoria, ano));
  }

  @GetMapping("/entidades/{cpfCnpj}")
  public Map<String, Object> entity(
      @PathVariable String cpfCnpj, @RequestParam(required = false) Integer ano) {
    Map<String, Object> doc = queries.entity(cpfCnpj, ano);
    if (doc == null) throw new NotFoundException("CPF/CNPJ sem registros: " + cpfCnpj);
    return doc;
  }

  @GetMapping("/finance")
  public Map<String, Object> finance(
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String dir,
      @RequestParam(required = false) String id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) String dateFrom,
      @RequestParam(required = false) String dateTo,
      @RequestParam(required = false) Long amountMin,
      @RequestParam(required = false) Long amountMax,
      @RequestParam(defaultValue = "false") boolean onlyPoliticianOwned) {
    return queries.finance(
        new FinanceRequest(
            scope,
            dir,
            id,
            page,
            q,
            sort,
            order,
            year,
            dateFrom,
            dateTo,
            amountMin,
            amountMax,
            onlyPoliticianOwned));
  }

  @GetMapping("/emendas")
  public Map<String, Object> earmarks(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String order) {
    return queries.earmarkPayments(page, q, order);
  }

  @GetMapping("/top-suppliers")
  public Map<String, Object> topSuppliers(@RequestParam(required = false) String year) {
    Integer y = year == null || year.equals("all") || year.isBlank() ? null : Integer.valueOf(year);
    return Map.of("suppliers", queries.topSuppliers(y));
  }

  @GetMapping("/ranking")
  public Map<String, Object> ranking(
      @RequestParam(required = false) String tipo,
      @RequestParam(required = false) Integer ano,
      @RequestParam(required = false) Integer page) {
    return queries.assetsRanking(tipo, ano, page);
  }

  @GetMapping("/sinais/doacao-circular")
  public Map<String, Object> circular(
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page) {
    return queries.circularDonations(severity, sort, page);
  }

  @GetMapping("/sinais/analise-ia")
  public Map<String, Object> aiReviews(
      @RequestParam(required = false) String verdict,
      @RequestParam(required = false) String rule,
      @RequestParam(required = false) Integer page) {
    return queries.aiReviews(verdict, rule, page);
  }

  @GetMapping("/sinais/socio-fornecedor")
  public Map<String, Object> supplierPartners(
      @RequestParam(required = false) String filter,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page) {
    return queries.supplierPartners(filter, q, page);
  }

  @GetMapping("/sinais/discurso")
  public Map<String, Object> discourse(
      @RequestParam(required = false) String categoria,
      @RequestParam(required = false) String grupo,
      @RequestParam(required = false) String severidade,
      @RequestParam(required = false) String handle,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page) {
    return queries.discourse(categoria, "1".equals(grupo), severidade, handle, q, page);
  }

  @GetMapping("/sinais/despesa-desproporcional")
  public Map<String, Object> disproportionate(
      @RequestParam(required = false) String categoria,
      @RequestParam(required = false) Integer ano,
      @RequestParam(required = false) Integer page) {
    return queries.disproportionateExpenses(categoria, ano, page);
  }

  @GetMapping("/graph/search")
  public Map<String, Object> graphSearch(@RequestParam(defaultValue = "") String q) {
    return queries.graphSearch(q);
  }

  @PostMapping("/graph/node")
  public Map<String, Object> graphNode(@RequestBody(required = false) DocRequest body) {
    return queries.graphNode(body == null ? "" : body.cpfCnpj());
  }

  @PostMapping("/graph/expand")
  public Map<String, Object> graphExpand(@RequestBody(required = false) DocRequest body) {
    return queries.graphExpand(body == null ? "" : body.cpfCnpj());
  }

  @PostMapping("/graph/paths")
  public Map<String, Object> graphPaths(@RequestBody(required = false) PathsRequest body) {
    return body == null
        ? Map.of("nodes", List.of(), "edges", List.of())
        : queries.graphPaths(body.newId(), body.existingIds());
  }

  public record DocRequest(String cpfCnpj) {}

  public record PathsRequest(String newId, List<String> existingIds) {}
}
