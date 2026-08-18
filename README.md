# StockLens

A small, well-tested stock screener for U.S. publicly traded companies, built for a
Software Quality course. One feature, done properly: search companies, filter by
P/E and market cap, sort by a financial metric.

- **Backend:** Java 21, Spring Boot 4.1, Gradle (wrapper included)
- **Frontend:** React + TypeScript + Vite
- **External data:** [Finnhub](https://finnhub.io) free tier — see
  [`docs/API-RESEARCH.md`](docs/API-RESEARCH.md) for the comparison of six APIs and
  why Finnhub was selected

## Documentation

| Document | For |
| --- | --- |
| [`docs/FUNDAMENTALS.md`](docs/FUNDAMENTALS.md) | **Learning the concepts** — layered architecture, controllers, services, providers, DTOs, dependency injection |
| [`docs/TESTING.md`](docs/TESTING.md) | **Testing** — AAA pattern, unit vs integration, performance, coverage |
| [`docs/API.md`](docs/API.md) | Using the REST API — endpoints, parameters, error catalog, config |
| **http://localhost:8080/swagger-ui.html** | Interactive API docs — browse and call the endpoints while the app runs |
| [`docs/openapi.yaml`](docs/openapi.yaml) | The same contract, machine-readable. **Generated** — refresh with `./gradlew exportOpenApiSpec` |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Changing the backend — layers, request flow, per-component notes, how to extend |
| [`docs/adr/`](docs/adr/) | Why the design is what it is — six decision records |
| [`docs/API-RESEARCH.md`](docs/API-RESEARCH.md) | Evidence behind the provider choice |

With the backend running, the API documents itself:

| URL | What |
| --- | --- |
| `/swagger-ui.html` | Swagger UI — the readable, clickable version |
| `/v3/api-docs` | The OpenAPI 3.1 spec as JSON |
| `/v3/api-docs.yaml` | The same spec as YAML |

Both are generated from the controller and its Javadoc by
[springdoc-openapi](https://springdoc.org), so they cannot drift from the code.
Set `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` to `false`
in a public deployment — Swagger UI is a live request console.

Javadoc on the public API is generated with `cd backend && ./gradlew javadoc`
(output in `backend/build/docs/javadoc/index.html`).

## Running it

### 0. Java

The backend needs JDK 21+. On macOS with Homebrew (`brew install openjdk`), the JDK
is not added to the PATH automatically; either add to your shell profile:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

or register it system-wide once (then `java` works everywhere, including GUI apps):

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk.jdk
```

### 1. Backend

Get a free API key at https://finnhub.io/register (no credit card), then:

```bash
cd backend
export FINNHUB_API_KEY=your_key_here
./gradlew bootRun          # serves http://localhost:8080
```

Without a key the app still starts and answers `503 Service Unavailable` with a
problem-detail body explaining that the key is missing.

### 2. Frontend

```bash
cd frontend
npm install
npm run dev                # serves http://localhost:5173, proxies /api to :8080
```

The browser only talks to the Spring Boot backend; the Finnhub key never reaches
the client.

### 3. Tests

```bash
cd backend
./gradlew test             # 66 tests, no network access, external API fully mocked
open build/reports/jacoco/test/html/index.html   # coverage: 97.5% instruction, 86.5% branch
```

See [`docs/TESTING.md`](docs/TESTING.md) for the concepts and what the coverage
report currently flags as untested.

## REST API

| Endpoint | Description |
| --- | --- |
| `GET /api/stocks` | Screener. Query params: `q` (name/ticker search), `maxPe` (> 0), `minMarketCap` (billions USD, ≥ 0), `sortBy` (`marketCap`, `pe`, `price`, `name`), `order` (`asc`/`desc`). Defaults: sort by market cap descending. |
| `GET /api/stocks/{ticker}` | Detail for one company from the screened universe. |

Errors follow RFC 9457 problem details: `400` for invalid parameters, `404` for an
unknown ticker, `503` when no data can be obtained from the provider.

## Architecture

```
React (Vite dev proxy)
        │  /api/stocks
        ▼
StockController          — HTTP contract, validation, problem-detail errors
        ▼
StockService             — in-memory TTL cache, search/filter/sort, degradation
        ▼
FinancialDataProvider    — interface isolating the external dependency
        ▼
FinnhubStockProvider     — RestClient, timeouts, typed failures (429 vs. rest)
        ▼
Finnhub REST API         — /stock/profile2, /quote, /stock/metric
```

Key decisions — summarised here, with the full context and trade-offs in
[`docs/adr/`](docs/adr/):

- **No database** ([ADR-001](docs/adr/0001-no-database.md)). The screener shows a *current* snapshot of 18 large-cap
  companies (configurable in `application.yml`); the natural store for that is an
  in-memory cache with a 10-minute TTL. PostgreSQL would add schema, migrations and
  container setup while providing nothing the MVP uses — no history, no
  user-specific data, no queries the in-memory list can't answer. Trade-offs: the
  cache is lost on restart (first request refetches, ~54 API calls) and instances
  don't share a cache. When persistence becomes useful (watchlists, historical
  charts), a `Repository` slots in behind `StockService` without touching the web
  layer.
- **Provider abstraction** ([ADR-002](docs/adr/0002-provider-abstraction.md)).
  `FinancialDataProvider` is one small interface; a second data source is a new
  implementation, not a rewrite.
- **Graceful degradation** ([ADR-003](docs/adr/0003-graceful-degradation.md)).
  Per-ticker failures are skipped; a rate limit aborts the refresh (retrying every
  remaining ticker would only burn quota); any failed refresh serves the previous
  snapshot; only a cold cache with a failing provider produces a 503.
- **Injected `Clock`** ([ADR-004](docs/adr/0004-inject-clock.md)). Time is a
  dependency, so a 10-minute TTL is tested in microseconds without sleeping.
- **Quota discipline.** One refresh = 3 calls × 18 tickers = 54 calls, under
  Finnhub's 60/minute. The TTL keeps steady-state usage ≈ 5.4 calls/minute, and
  automated tests never touch the real API.

## Testing strategy

| Layer | Class | Approach |
| --- | --- | --- |
| Screener logic | `StockServiceTest` (29 tests) | Mockito-mocked provider; search hit/miss, blank search, filter boundaries (inclusive limit, zero, negative P/E, huge values, missing metrics), sort directions with nulls-last, TTL caching, stale-serving, rate-limit abort |
| API client | `FinnhubStockProviderTest` (10) | Local MockWebServer; success mapping, unknown ticker, HTTP 500, HTTP 429, timeout, invalid JSON, missing fields, missing API key fails fast |
| Web layer | `StockControllerTest` (13) | `@WebMvcTest` with mocked service; JSON contract, defaults, validation (negative/zero/non-numeric filters, bad sort field, malformed ticker), 404/503 mapping |
| Integration | `StockScreenerIntegrationTest` (3) | `@SpringBootTest` + MockMvc, only the provider mocked; full HTTP→controller→service flow incl. cold-cache outage → 503 |
| API docs | `OpenApiDocsTest` (5) | `@SpringBootTest`; both endpoints present in the generated spec, error responses attached, descriptions populated from Javadoc, Swagger UI reachable |
| Performance | `StockServicePerformanceTest` (3), `StockScreenerPerformanceTest` (2) | JUnit 5 `assertTimeout` regression guards over synthetic 1,000–10,000-item universes; service level and full HTTP stack |

Every class above marks its Arrange/Act/Assert phases explicitly with
comments — see [`docs/TESTING.md`](docs/TESTING.md#2-the-aaa-pattern) for
where and why.

Testcontainers was deliberately not used: there is no database, so it would be
technology for its own sake.

## Future roadmap (not implemented)

MVP → user accounts → watchlists → virtual portfolios → investment scoring →
price alerts → historical charts → financial news. The layering above is what
keeps these cheap to add later: new features get their own service/repository
behind the same controller pattern, and heavier data needs would introduce
PostgreSQL behind `StockService` (or a second `FinancialDataProvider` for a
provider with historical data).

StockLens displays data only; it makes no investment recommendations.
