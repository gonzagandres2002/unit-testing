# StockLens Backend Architecture

Audience: a developer about to change this code. For the HTTP contract see
[`API.md`](API.md); for *why* the structure is this way see [`adr/`](adr/).

New to layered backends, or need a refresher on what a controller, service,
provider or DTO actually is? Start with
[`FUNDAMENTALS.md`](FUNDAMENTALS.md) — it teaches the concepts using this
codebase as the example, and this file assumes them.

## Overview

Four layers, one direction of dependency. Each layer knows only the layer
directly beneath it, and each is replaceable without touching its neighbours.

```
React (Vite dev proxy)
        │  GET /api/stocks
        ▼
StockController          — HTTP contract, validation, no try/catch
        ▼
StockService             — screening logic, TTL cache, degradation policy
        ▼
FinancialDataProvider    — interface: the external dependency, abstracted
        ▼
FinnhubStockProvider     — RestClient, timeouts, typed failures
        ▼
Finnhub REST API         — /stock/profile2, /quote, /stock/metric
```

The single most consequential line in the codebase is the one that isn't
there: `StockService` has no import of anything Finnhub-related. It depends on
`FinancialDataProvider`, an interface with one method. That seam is what makes
the service unit-testable without a network, and what makes a second data
source an addition rather than a rewrite.

## Request flow

A successful `GET /api/stocks?maxPe=30&sortBy=pe&order=asc`:

1. **`StockController.search`** — Spring binds the query parameters. Bean
   validation (`@Positive`, `@PositiveOrZero`) rejects out-of-range numbers
   before any code runs. `SortBy.from` / `Direction.from` parse the strings
   into enums, throwing `IllegalArgumentException` on anything unrecognised.
2. A **`StockQuery`** record is built. From here on the service handles only
   validated, typed values — never raw user input.
3. **`StockService.search`** asks for the current snapshot. If the cache is
   warm and unexpired, this is a field read and no I/O happens.
4. On a cold or expired cache, **`refresh()`** loops the configured tickers
   and calls the provider for each.
5. **`FinnhubStockProvider.fetchStock`** issues up to three HTTP calls per
   ticker and maps the JSON into a `Stock`, translating any HTTP failure into
   a domain exception.
6. The snapshot is filtered, sorted and returned. Jackson serializes the
   records to JSON.

Any exception escaping step 1–6 is caught by **`GlobalExceptionHandler`** and
converted to an RFC 9457 problem detail.

---

## Components

### Web layer — `com.stocklens.web`

**Overview.** Two `GET` endpoints and an exception handler. Deliberately thin:
if logic appears here, it belongs in the service.

**Responsibilities**
- Bind and validate query/path parameters
- Parse strings into domain enums
- Delegate to `StockService`
- Map every exception to a problem detail

**Notable.** `StockController` contains **no `try`/`catch`**. Errors propagate
to `GlobalExceptionHandler`, which selects a handler by exception *type*. New
failure modes are added by writing a class and a handler method, not by
editing the controller.

**Dependencies:** `StockService`, Spring Web MVC, Jakarta Bean Validation.

---

### Service layer — `com.stocklens.service`

**Overview.** All business logic: search, filter, sort, cache, and the
degradation policy. Knows nothing about HTTP in either direction — no servlet
types, no status codes, no Finnhub.

**Caching.** An `AtomicReference<Snapshot>` holds the last successful fetch.
`refresh()` is `synchronized` with a double-check inside, so a burst of
concurrent requests on a cold cache produces one refresh, not N.

**Degradation policy** — the heart of the service, expressed purely through
exception types:

| Situation | Behaviour |
| --- | --- |
| One ticker fails (`FinancialDataException`) | Logged and skipped; the rest of the universe is still served |
| Rate limit hit (`RateLimitedException`) | Refresh **aborts immediately** — retrying the remaining tickers would only burn quota |
| Refresh failed, previous snapshot exists | **Stale snapshot served** with a `200`; clients judge freshness via `lastUpdated` |
| Refresh failed, cache is cold | `DataUnavailableException` → `503` |

Note the nested `try` in `refresh()`: the inner block skips a broken ticker
and continues the loop, while `throw e` on a rate limit escapes to the outer
block, abandoning the loop entirely. Two failure modes, two behaviours, no
string inspection.

**Sorting.** Three comparators chain: nulls-last, then the metric, then ticker
as tiebreaker. The tiebreaker is what makes results deterministic, which is
what lets tests assert exact ordering.

**Dependencies:** `FinancialDataProvider` (interface only), `StockLensProperties`, `Clock`.

---

### Provider layer — `com.stocklens.provider`

**Overview.** The boundary with the outside world, and the only place that
knows Finnhub exists.

**The contract** (`FinancialDataProvider`) distinguishes three outcomes,
because the service reacts differently to each:

- `Optional` with a value → data retrieved
- `Optional.empty()` → provider answered fine, doesn't know this ticker
- `RateLimitedException` → stop calling
- `FinancialDataException` → skippable failure

An unknown ticker is **not** an exception. It is an expected outcome of
screening a configured list, not an error.

**Translation.** `FinnhubStockProvider` catches Spring's
`RestClientResponseException` / `ResourceAccessException` and rethrows domain
exceptions with the original as the `cause`. Nothing above this layer imports
a Spring HTTP type.

**Quirks handled here, documented because they are not general HTTP:**
- Finnhub answers an unknown symbol with `200 OK` and `{}` — hence the empty
  profile check
- It answers a delisted symbol with an all-zero quote — hence the `c == 0`
  check
- `profile2` reports market cap in **millions**; converted to plain USD at the
  boundary so the unit is unambiguous everywhere else
- Metric field names are undocumented, so several known keys are tried in
  order and the metric stays optional
- A read timeout can fire *while the body is being consumed*, surfacing as a
  generic `RestClientException` rather than `ResourceAccessException`;
  `hasIoCause()` walks the cause chain to classify it correctly

**Call cost.** Up to three calls per ticker, short-circuited early: a missing
API key spends none, an unknown ticker one, a delisted ticker two.

**Dependencies:** Spring `RestClient`, Jackson, `Clock`.

---

### Domain — `com.stocklens.domain`

**Overview.** One record, `Stock`. Immutable, no behaviour, no framework
annotations.

**Nullability is the design.** Every metric is a boxed `Double`, not a
primitive, because the provider genuinely does not have every metric for every
company. Making them primitives would force a fake `0.0` that would then sort
and filter as if it were real data.

---

### Configuration — `com.stocklens.config`

**`StockLensProperties`** binds the `stocklens.*` block into nested records —
API key, timeouts, universe, TTL. No magic strings elsewhere in the code.

**`AppConfig`** exposes two beans:

- **`Clock`** — the single most important testing decision in the project.
  Because time is injected instead of `Instant.now()` being called inline,
  TTL-expiry tests run in microseconds with a hand-advanced clock instead of
  sleeping for ten minutes.
- **`RestClient`** — with both connect and read timeouts set. A refresh makes
  up to 54 serial calls on the request thread, so an unbounded read would let
  one unresponsive provider stall the endpoint.

**`OpenApiConfig`** describes the API for `/v3/api-docs` and Swagger UI. It
sets the title, licence and servers, and registers an `OpenApiCustomizer` that
attaches the `400`/`404`/`503` responses to every operation — applied globally
for the same reason the errors themselves are: `GlobalExceptionHandler` maps
them for all endpoints at once, so annotating each handler method would
restate the same four responses per endpoint and drift the first time one
changed. Operation and parameter *descriptions* are not configured there; they
are read from Javadoc at runtime via therapi.

**Configuration reference:** see the table in [`API.md`](API.md#configuration).

---

## Testing architecture

52 tests, three tiers, no network access. Run with `./gradlew test`.

| Tier | Class | Spring? | External dependency |
| --- | --- | --- | --- |
| Unit | `StockServiceTest` (27) | none | Mockito-mocked provider, hand-advanced clock |
| Unit | `FinnhubStockProviderTest` (10) | none | Real local HTTP server (MockWebServer) |
| Slice | `StockControllerTest` (11) | `@WebMvcTest` — web layer only | Mocked service |
| Integration | `StockScreenerIntegrationTest` (3) | `@SpringBootTest` — full context | Only the provider mocked |
| Smoke | `StocklensApplicationTests` (1) | `@SpringBootTest` | — |
| Docs | `OpenApiDocsTest` (5) | `@SpringBootTest` | — |

Two details that are easy to break:

- `StockScreenerIntegrationTest` needs
  `@DirtiesContext(AFTER_EACH_TEST_METHOD)` because `StockService`'s cache is
  stateful. Without it, a snapshot cached by an earlier test leaks into the
  cold-cache `503` test and it fails.
- `FinnhubStockProviderTest` sets 500 ms timeouts and delays a response by
  2 s to exercise the timeout path. Shortening the delay or lengthening the
  timeout silently disables that test's purpose.

`OpenApiDocsTest` is worth a note: generated documentation can still be
generated *wrongly*. It pins that both endpoints appear, that the error
responses are attached (and that `404` is **not** attached to the screener,
which returns `[]` instead), and that descriptions are actually populated from
Javadoc — which silently become empty if the therapi annotation processor is
dropped from `build.gradle`. It also exports the spec that
`./gradlew exportOpenApiSpec` copies to `docs/openapi.yaml`.

**No Testcontainers**, because there is no database — see
[ADR-001](adr/0001-no-database.md).

---

## Extending it

| To add… | Do this |
| --- | --- |
| A second data source | Implement `FinancialDataProvider`. Nothing above changes. |
| A new filter | Add a component to `StockQuery`, a `removeIf` in `search`, a `@RequestParam` in the controller. |
| A new sort field | Add a constant to `SortBy` with its comparator, plus a case in `missingMetricLast`. |
| Persistence (watchlists, history) | A repository behind `StockService`. The web layer does not move. See [ADR-001](adr/0001-no-database.md). |
| A new error type | A `RuntimeException` subclass plus a handler method in `GlobalExceptionHandler`. If it applies to every endpoint, add it to `OpenApiConfig.errorResponses()` too. |

The layering exists to keep each of these local. If a change forces edits in
all four layers at once, that is a signal the abstraction is wrong — not that
the layers should be bypassed.
