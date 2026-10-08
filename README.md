# 🔎 EloSys — Backend

API e coletores do **EloSys**: a ficha pública de qualquer candidato brasileiro, montada só com dados abertos por lei (TSE, Receita Federal, Portal da Transparência), com a **proveniência de cada dado** à vista: de qual arquivo saiu, quando foi baixado e o SHA-256 que comprova que não foi alterado.

Escrito em **Java 25** e **Spring Boot 4.1** com **arquitetura hexagonal**, **Postgres 17** como base, **Redis 7** como cache de leitura e lock dos jobs, **Resilience4j** nas chamadas às fontes e **Claude Haiku** para revisar os sinais de alerta.

Frontend: [elosys-frontend](https://github.com/niicsz/elosys-frontend) (Angular 21).

> **Créditos:** o EloSys foi idealizado e criado por **Yuri Rousseff** ([github.com/YuriRDev/elosys](https://github.com/YuriRDev/elosys)). A ideia, as fontes, as regras de detecção, o léxico de discurso, os prompts de revisão e o desenho da interface vêm do projeto original. Este repositório reescreve a arquitetura: coletores e regras em Java, Postgres + Redis no lugar de SQLite e revisão por IA com Claude.

---

## 🧭 Do arquivo oficial à tela

```
Fontes oficiais (TSE · Receita/BrasilAPI · Portal da Transparência · X)
  │
  ├─ Coletores (jobs) ── download resiliente ──► source_record (URL, data, SHA-256)
  │        │
  │        └─ COPY em lote → staging → INSERT … ON CONFLICT DO NOTHING ──► Postgres
  │
  ├─ Regras ── doação circular · despesa desproporcional · sócio-fornecedor ──► signal
  │
  ├─ Revisão por IA (Claude Haiku, saída estruturada) ──► bizarro / inconclusivo / plausível
  │
  ├─ REFRESH MATERIALIZED VIEW CONCURRENTLY  →  limpa o cache  →  reaquece as telas
  │
  └─ API de leitura ── @Cacheable no Redis (fail-open) ──► elosys-frontend
```

Todo job de escrita roda sob um **lock distribuído no Redis** (`SET NX` + watchdog): só um escreve por vez, e cada execução fica registrada em `job_run`, com relatório em `reports/`.

## 🚨 Sinais de alerta

Um sinal é um padrão nos dados que merece um olhar mais de perto. **Não é acusação.**

| Regra | O que procura | Severidade |
|---|---|---|
| `circular_donations` | Dinheiro que sai de uma campanha e, seguindo doações e despesas, volta para a mesma cadeia. Grafo em CSR, Tarjan iterativo para achar as componentes fortemente conexas e DFS limitada (profundidade 5, hubs com mais de 400 vizinhos ignorados, mínimo de R$ 10 mil) | alta para ciclos de até 3 nós |
| `disproportionate_expense` | Gasto em itens baratos (canetas, adesivos, crachás…) comparado com a mediana histórica da categoria e com pares de mesmo cargo e UF | média acima de 15× a mediana, alta acima de 30× |
| `candidate_supplier_partner` | Candidato no quadro societário de uma empresa que recebeu dinheiro de campanha (nome normalizado + 6 dígitos visíveis do CPF; casos ambíguos são descartados) | — |
| discurso (`social-review`) | Posts do X das contas declaradas ao TSE, filtrados por um léxico de ~470 termos e classificados pelo modelo dentro do contexto | alta / média / baixa |

O job `ai-review` manda cada sinal, com os fatos, para o Claude, que responde com **saída estruturada** (veredito, confiança, explicação e fatos citados). O modelo padrão é `claude-haiku-4-5` e pode ser trocado por `ANTHROPIC_MODEL` (por exemplo, `claude-haiku-5-5`).

## 🛡️ Resiliência

Cada fonte tem uma instância nomeada do Resilience4j em `ResilienceFacade`. O retry fica sempre por fora, então cada nova tentativa passa pelo disjuntor e o sistema não insiste numa fonte que já caiu.

| Instância | Uso | Composição |
|---|---|---|
| `bulk-download` | ZIPs do TSE e do Portal da Transparência | `Retry(CircuitBreaker(call))` |
| `lookup-api` | BrasilAPI (CNPJ), DivulgaCandContas | `Retry(CircuitBreaker(RateLimiter(Bulkhead(call))))` |
| `apify` | coleta de posts do X | `Retry(CircuitBreaker(call))` |
| `llm` | Claude (sinais e posts) | `Retry(CircuitBreaker(Bulkhead(TimeLimiter(call))))` |

Só `TransientFailure` (timeout, 5xx, 429) dispara retry, sempre com backoff exponencial e jitter. Um 4xx definitivo falha na hora. Se o Redis cair, a API continua respondendo direto do Postgres: o `CacheErrorHandler` trata erro de cache como miss.

## ⚡ Leitura rápida

A base completa tem ~42 milhões de linhas. As telas pesadas leem de **materialized views** (migrações `V3` e `V4`) com índice único, atualizadas com `CONCURRENTLY` depois de cada job de escrita, e as respostas ficam 30 minutos no Redis.

| Tela | Consulta direta nas tabelas | Com as views |
|---|---|---|
| ranking de bens | 90–193 s | ~0,03 s com cache |
| despesa desproporcional | ~21 s | ~0,5 s sem cache · ~0,02 s com cache |
| início | dava timeout a frio | ~0,2 s |

## 🏗️ Arquitetura

```
src/main/java/com/binitech/elosys
├── domain                      # Java puro: SourceValues, proveniência, sinais, MoneyGraph + CycleFinder, léxico
├── application
│   ├── ports/inbound           # JobUseCasePort, ReadModelQueryPort
│   ├── ports/outbound          # repositórios, download, LLM, scraper, cache, lock, arquivos
│   └── usecases                # JobUseCase, ReadModelUseCase
│       ├── collectors          # TSE, Receita, Transparência, X
│       ├── rules               # doação circular, despesa desproporcional, sócio-fornecedor
│       └── review              # revisão de sinais e posts por IA
├── adapters
│   ├── inbound/web             # API de leitura, API de admin (X-Admin-Token), tratamento de erros
│   ├── inbound/cli             # jobs pela linha de comando
│   ├── inbound/cache           # aquecimento do cache
│   └── outbound                # JDBC (COPY, streaming), HTTP + Resilience4j, Claude, Apify,
│                               # Redis (cache e lock), arquivos, importador SQLite
└── config                      # beans, Resilience4j, Redis, Anthropic, CORS
```

As regras de dependência são verificadas pelo `ArchitectureTest` (ArchUnit): `domain` e `application` não conhecem Spring, JDBC, Redis nem o SDK da Anthropic.

- **Banco:** migrações Flyway em `src/main/resources/db/migration` (32 tabelas, índices de leitura e materialized views)
- **Contrato:** Swagger UI em `/swagger-ui.html`
- **Métricas:** `/actuator/prometheus`, incluindo as do Resilience4j

## 🔌 API

| Método | Rota | |
|---|---|---|
| GET | `/api/search?q=` | busca de candidatos e pessoas por nome ou CPF |
| GET | `/api/home?ano=` · `/api/meta` · `/api/top-suppliers?year=` | início; anos, categorias e contadores; maiores fornecedores |
| GET | `/api/politicos/{id}?ano=` | ficha completa do candidato |
| GET | `/api/politicos/{id}/despesas-categoria` | despesas de uma categoria |
| GET | `/api/entidades/{cpfCnpj}?ano=` | ficha de CPF/CNPJ (redireciona se for candidato) |
| GET | `/api/finance` | doações e despesas paginadas, com filtros de data, valor e "empresa de político" |
| GET | `/api/ranking?tipo=bens\|crescimento` · `/api/emendas` | rankings de patrimônio e emendas pagas a empresas |
| GET | `/api/sinais/{doacao-circular\|despesa-desproporcional\|socio-fornecedor\|analise-ia\|discurso}` | sinais de alerta |
| GET/POST | `/api/graph/search` · `/node` · `/expand` · `/paths` | grafo de correlações |
| GET/POST | `/api/admin/jobs` · `/{name}` · `/runs/{id}` | disparo e acompanhamento de jobs |

## 🚀 Como rodar

Pré-requisitos: Java 25, Maven 3.9+ e Docker.

```bash
docker compose up -d postgres redis

cp .env.example .env            # ajuste o ELOSYS_ADMIN_TOKEN e as chaves
set -a && source .env && set +a # exporta as variáveis no shell

mvn -DskipTests package
java -jar target/elosys-backend-0.1.0.jar   # http://localhost:8080
```

O `application.yml` não tem valores padrão: toda variável da tabela de [variáveis de ambiente](#variáveis-de-ambiente) precisa estar definida, mesmo que vazia, ou a aplicação não sobe.

As migrações rodam na subida. Na base completa, a criação das materialized views leva uns 9 minutos na primeira vez.

Para começar com dados, dá para importar a base SQLite do projeto original em vez de coletar tudo de novo:

```bash
java -jar target/elosys-backend-0.1.0.jar import-sqlite --path=../elosys/elosys.db
```

API e frontend em containers (com o frontend clonado em `../elosys-frontend`):

```bash
docker compose --profile app up -d --build   # frontend em http://localhost:4200
```

### Jobs

Com o nome do job como argumento, a aplicação roda só esse job e encerra, sem subir o servidor web:

```bash
java -jar target/elosys-backend-0.1.0.jar tse-candidates --years=2022,2024
java -jar target/elosys-backend-0.1.0.jar rule-circular-donations --max-depth=5
java -jar target/elosys-backend-0.1.0.jar ai-review --limit=100
```

Ou pela API de admin:

```bash
curl -X POST localhost:8080/api/admin/jobs/rule-circular-donations \
  -H "X-Admin-Token: $ELOSYS_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"max-depth":"5"}'
```

| Job | O que faz |
|---|---|
| `tse-candidates`, `tse-accounts`, `tse-assets`, `tse-social`, `tse-photo-urls` | candidaturas, prestação de contas, bens, redes sociais e fotos |
| `receita-cnpj` | cadastro e quadro societário (BrasilAPI) |
| `transparencia-sanctions`, `transparencia-earmarks` | sanções CEIS/CNEP e emendas parlamentares |
| `social-x` | posts do X das contas declaradas (Apify) |
| `rule-circular-donations`, `rule-disproportionate-expense`, `candidate-supplier-partner` | regras de detecção |
| `ai-review`, `social-review` | revisão por IA de sinais e posts |
| `import-sqlite` | importa o `elosys.db` do projeto original |

### Variáveis de ambiente

Todas são obrigatórias. O [`.env.example`](.env.example) traz os valores para rodar local; as marcadas como "pode ficar vazia" desligam a funcionalidade correspondente.

| Variável | Exemplo local | |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `elosys`, `elosys`, `elosys` | Postgres |
| `DB_POOL_SIZE` | `20` | conexões no pool |
| `DB_SSLMODE`, `DB_SSL_NEGOTIATION` | `disable`, `postgres` | TLS do Postgres; para um banco atrás de um router com TLS passthrough (OpenShift), `require` e `direct` |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis |
| `REDIS_USER`, `REDIS_PASSWORD` | vazias | autenticação do Redis (pode ficar vazia) |
| `PORT` | `8080` | porta HTTP |
| `ELOSYS_TMP_DIR`, `ELOSYS_REPORTS_DIR` | `dados_tmp`, `reports` | downloads e relatórios dos jobs |
| `ELOSYS_CACHE_TTL` | `30m` | validade do cache |
| `ELOSYS_ADMIN_TOKEN` | `troque-isto` | token da API de jobs (vazia desliga o admin) |
| `ELOSYS_ALLOWED_ORIGINS` | `http://localhost:4200` | CORS |
| `ANTHROPIC_API_KEY` | vazia | revisão por IA (pode ficar vazia) |
| `ANTHROPIC_MODEL`, `ANTHROPIC_TIMEOUT` | `claude-haiku-4-5`, `60s` | modelo e timeout por chamada |
| `APIFY_TOKEN` | vazia | coleta do X (pode ficar vazia) |
| `APIFY_ACTOR` | `kaitoeasyapi~twitter-x-data-tweet-scraper-pay-per-result-cheapest` | actor da Apify |
| `ELOSYS_CURL_IMPERSONATE` | vazia | caminho do curl-impersonate (pode ficar vazia) |

### Bloqueio do TSE

O CDN do TSE (Akamai) às vezes bloqueia clientes que não são navegadores pelo fingerprint TLS. Há duas saídas:

- baixar o ZIP no navegador e deixar em `dados_tmp/` com o mesmo nome: o coletor usa o arquivo local e registra a proveniência normalmente (URL + SHA-256);
- apontar `ELOSYS_CURL_IMPERSONATE` para um binário do [curl-impersonate](https://github.com/lwthiker/curl-impersonate).

## 🧪 Testes

```bash
mvn test
```

Cobrem regras de arquitetura (ArchUnit), normalização dos dados das fontes, resolução de identidade, parsers e regras de detecção, ciclos no grafo e léxico.

## ☁️ Deploy

| Peça | Onde |
|---|---|
| API (este repositório) e frontend | Railway, com deploy automático a cada push na `main` |
| Redis | Railway, na rede privada do projeto |
| Postgres 17 (~15 GB) | OpenShift, com volume de 60 GB e acesso externo por uma Route TLS passthrough |

O Postgres fica atrás do router do OpenShift, que só encaminha TLS pelo SNI. Por isso a API conecta com `DB_SSLMODE=require` e `DB_SSL_NEGOTIATION=direct` na porta 443 (negociação TLS direta do Postgres 17, suportada pelo pgjdbc).

A configuração do serviço no Railway é declarada em [`.railway/railway.ts`](.railway/railway.ts): fonte no GitHub, build pelo Dockerfile, healthcheck, política de restart e variáveis. Os segredos (`DB_PASSWORD`, `ELOSYS_ADMIN_TOKEN`, `ANTHROPIC_API_KEY`, `APIFY_TOKEN`) usam `preserve()`: o valor fica só no Railway e nunca no repositório. O `package.json` da raiz existe apenas para o SDK do Railway usado por esse arquivo.

```bash
npm install
railway config plan    # mostra o que mudaria no Railway
railway config apply
```
