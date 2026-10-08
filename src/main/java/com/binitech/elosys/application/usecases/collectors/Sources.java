package com.binitech.elosys.application.usecases.collectors;

import com.binitech.elosys.domain.provenance.SourceDefinition;

public final class Sources {
  private Sources() {}

  public static final SourceDefinition TSE_CANDIDATES =
      new SourceDefinition(
          "TSE - consulta_cand",
          "Tribunal Superior Eleitoral",
          "csv",
          "https://cdn.tse.jus.br/estatistica/sead/odsele/consulta_cand/",
          "Brazilian electoral open data. Candidacy registration is public (Lei 9.504/1997 art."
              + " 11; TSE open-data resolutions). CPF is a non-secret registry field (masked only"
              + " in 2024, reverted for 2026).",
          "Candidate home address, phone and personal e-mail are protected and not ingested.");

  public static final SourceDefinition TSE_ACCOUNTS =
      new SourceDefinition(
          "TSE - prestacao_contas",
          "Tribunal Superior Eleitoral",
          "csv",
          "https://cdn.tse.jus.br/estatistica/sead/odsele/prestacao_contas/",
          "Brazilian electoral open data. Campaign finance reporting, including every donation"
              + " received, is public by law (Lei 9.504/1997 arts. 28-32; TSE open-data"
              + " resolutions). The campaign CNPJ is a public registry identifier (Receita natureza"
              + " jurídica 409-4).",
          "Donor/supplier home address and personal contact are not published here and are not"
              + " ingested; only name, CPF/CNPJ, state/municipality and the transaction itself.");

  public static final SourceDefinition TSE_SOCIAL =
      new SourceDefinition(
          "TSE - rede_social_candidato",
          "Tribunal Superior Eleitoral",
          "csv",
          "https://cdn.tse.jus.br/estatistica/sead/odsele/consulta_cand/",
          "Declaring social media / website URLs is mandatory as part of the RRC (Requerimento de"
              + " Registro de Candidatura) since Res. TSE 23.610/2019 art. 26-A. Published as open"
              + " data by the same resolution.",
          "Only the URL the candidate declared to the TSE is ingested — no scraping of the profile"
              + " itself, no follower/contact data.");

  public static final SourceDefinition TSE_ASSETS =
      new SourceDefinition(
          "TSE - bem_candidato",
          "Tribunal Superior Eleitoral",
          "csv",
          "https://cdn.tse.jus.br/estatistica/sead/odsele/bem_candidato/",
          "Declaring assets is mandatory at candidacy registration (Lei 9.504/1997 art. 11 §1"
              + " IV). Published as open data by TSE resolutions.",
          "Only the declared value/description is ingested — no supporting documents.");

  public static final SourceDefinition TSE_PHOTOS =
      new SourceDefinition(
          "TSE - DivulgaCandContas fotoUrl",
          "Tribunal Superior Eleitoral",
          "api",
          "https://divulgacandcontas.tse.jus.br/divulga/rest/v1/candidatura/pesquisar",
          "Foto oficial enviada no registro de candidatura, publicada como dado aberto por"
              + " resolucoes do TSE; URL obtida via a API de busca (por CPF) do sistema"
              + " DivulgaCandContas (voltado ao publico geral, nao documentado como API estavel).",
          "Interno/nao documentado -- ver comentario em schema.sql acima de candidate_photo.");

  public static final SourceDefinition BRASILAPI_CNPJ =
      new SourceDefinition(
          "BrasilAPI - CNPJ",
          "BrasilAPI (proxy da Receita Federal)",
          "api",
          "https://brasilapi.com.br/api/cnpj/v1/",
          "Cadastro Nacional da Pessoa Juridica (CNPJ) e seu quadro societario sao dados publicos"
              + " da Receita Federal (Lei de Acesso a Informacao, Lei 12.527/2011). BrasilAPI e um"
              + " proxy publico e gratuito sobre esses dados oficiais.",
          "Fetched incrementally, one CNPJ per HTTP request -- see schema.sql comment above"
              + " company_registry for why this is not rewrite-only like the rest of elosys.");

  public static final SourceDefinition SANCTIONS =
      new SourceDefinition(
          "Portal da Transparencia - CEIS/CNEP",
          "Controladoria-Geral da Uniao (CGU)",
          "csv",
          "https://portaldatransparencia.gov.br/download-de-dados/",
          "Cadastro publico de sancoes por licitacao/contrato (Lei 8.666/1993, Lei 14.133/2021) e"
              + " por atos de improbidade/corrupcao (Lei 8.429/1992, Lei 12.846/2013 - Lei"
              + " Anticorrupcao). Publicacao obrigatoria por determinacao legal.",
          "Snapshot diario do estado atual do cadastro — nao ha historico de anos.");

  public static final SourceDefinition EARMARKS =
      new SourceDefinition(
          "Portal da Transparencia - Emendas Parlamentares",
          "Controladoria-Geral da Uniao (CGU)",
          "csv",
          "https://portaldatransparencia.gov.br/download-de-dados/emendas-parlamentares",
          "Execucao orcamentaria de emendas parlamentares individuais, de bancada e de comissao"
              + " (Constituicao art. 166), publicacao obrigatoria como dado aberto.",
          "Arquivo unico cobrindo todo o historico (2014-atual); nao ha recorte por ano na fonte.");

  public static final SourceDefinition X_POSTS =
      new SourceDefinition(
          "X/Twitter (via Apify apidojo/tweet-scraper)",
          "X Corp. (conteúdo público) / coleta via Apify",
          "scraping",
          "https://x.com/",
          "Manifestações públicas de agentes e candidatos a cargo público em rede social aberta."
              + " As contas são as declaradas pelos próprios candidatos ao TSE"
              + " (rede_social_candidato, Res. TSE 23.610/2019). Conteúdo coletado só de perfis"
              + " públicos, texto apenas.",
          "Conteúdo efêmero: não entra no manifest.json / elosys verify. A integridade é o payload"
              + " cru + sha256 + retrieved_at em social_post (ver schema.sql e"
              + " elosys/social/__init__.py).");
}
