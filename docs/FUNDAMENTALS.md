# Backend Fundamentals

A refresher on how this kind of application is built, using StockLens as the
worked example. Every class named here is real and you can open it.

If you want the *what* of this codebase, read [`ARCHITECTURE.md`](ARCHITECTURE.md).
This file is the *why it looks like that at all*.

**Read in order:** §1–3 give you the mental model. §4–11 define each piece.
§12–14 cover the language mechanics (interfaces, inheritance, injection).
§15 onward is reference.

---

## 1. What a backend actually is

A program that sits at a network address, waits for HTTP requests, and answers
them. That's it.

```
browser  ──── GET /api/stocks?maxPe=30 ────►  your program
         ◄─── 200 OK  [{"ticker":"MSFT",...}] ───
```

Everything else — layers, services, injection — exists to keep that program
from turning into a mess as it grows.

The unit of work is a **request**. One request comes in, some code runs, one
response goes out. Requests are independent and concurrent: several can be in
flight at the same moment, on different threads.

---

## 2. The problem layers solve

Imagine writing the whole thing in one method:

```java
@GetMapping("/api/stocks")
public String everything(HttpServletRequest request) {
    String maxPe = request.getParameter("maxPe");        // parse HTTP
    if (maxPe != null && Double.parseDouble(maxPe) <= 0) // validate
        return "{\"error\":\"bad maxPe\"}";
    var http = HttpClient.newHttpClient();               // call Finnhub
    var response = http.send(...);
    var json = new ObjectMapper().readTree(response.body());
    // filter, sort, cache, handle errors, build JSON...
}
```

This works. It also has four problems:

| Problem | Consequence |
| --- | --- |
| To test the filtering, you must make a real HTTP request | Tests are slow, flaky, need the network, burn API quota |
| Business rules are tangled with HTTP parsing | You can't reuse the screening logic anywhere else |
| Swapping Finnhub for another API means rewriting this method | The external vendor is welded into your feature |
| Everything is in one place | Two people can't work on it, and one bug can break all of it |

**Layering is the fix.** Split the work into groups with one job each, and let
each group depend only on the next one down. That's the whole idea. The rest
is naming conventions.

---

## 3. The layers in this app

```
                  HTTP request
                       │
                       ▼
┌──────────────────────────────────────────────┐
│ web/        StockController                  │  Speaks HTTP.
│             GlobalExceptionHandler           │  Knows nothing about Finnhub.
└──────────────────────────────────────────────┘
                       │  calls
                       ▼
┌──────────────────────────────────────────────┐
│ service/    StockService                     │  Business rules.
│             StockQuery                       │  Knows nothing about HTTP.
└──────────────────────────────────────────────┘
                       │  calls
                       ▼
┌──────────────────────────────────────────────┐
│ provider/   FinancialDataProvider  «interface»│  The contract.
└──────────────────────────────────────────────┘
                       ▲  implemented by
┌──────────────────────────────────────────────┐
│ provider/finnhub/  FinnhubStockProvider      │  Talks to the outside world.
└──────────────────────────────────────────────┘
                       │
                       ▼
                 Finnhub REST API

┌──────────────────────────────────────────────┐
│ domain/     Stock                            │  Data. Used by every layer.
│ config/     AppConfig, StockLensProperties   │  Wiring and settings.
└──────────────────────────────────────────────┘
```

### The one rule

**Dependencies point down, never up.** `web` may call `service`. `service` may
call `provider`. Never the reverse.

This is not a guideline you have to take on faith — it is checkable. Here is
every internal import in the codebase:

| Class | Imports from other packages |
| --- | --- |
| `StockController` | `domain.Stock`, `service.StockQuery`, `service.StockService` |
| `GlobalExceptionHandler` | `service.DataUnavailableException`, `service.StockNotFoundException` |
| `StockService` | `domain.Stock`, `provider.*`, `config.StockLensProperties` |
| `FinnhubStockProvider` | `domain.Stock`, `provider.*`, `config.StockLensProperties` |
| `FinancialDataProvider` | `domain.Stock` |
| `Stock` | *nothing* |

Read the first two rows again: **nothing in `web` imports `provider`.** The web
layer has no idea Finnhub exists. And `Stock` imports nothing at all, which is
why every layer can use it freely.

---

## 4. Controller

**What it is.** The class that maps a URL to a Java method.

```java
@RestController
@RequestMapping(path = "/api/stocks", produces = MediaType.APPLICATION_JSON_VALUE)
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {   // handed in, not created
        this.stockService = stockService;
    }

    @GetMapping                                            // GET /api/stocks
    public List<Stock> search(
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) @Positive Double maxPe,
            ...) {
        return stockService.search(new StockQuery(...));   // delegate immediately
    }
}
```

**What it does.**

1. Receives the HTTP request. Spring converts `?maxPe=30` into a `Double` for you.
2. Validates the input. `@Positive` rejects `maxPe=0` before your code runs.
3. Converts raw strings into typed values (`"pe"` → `SortBy.PE`).
4. Calls the service.
5. Returns an object. Spring converts it to JSON automatically.

**Why have one at all?** Because it is the *only* place that knows about HTTP.
Status codes, query parameters, JSON, headers — all confined here. Everything
below it is plain Java. That confinement is what makes the layers below
testable without a server.

**What does NOT belong in a controller:** filtering, sorting, calculations,
caching, database or API calls, business rules of any kind. If you're writing
an `if` about *stocks* rather than about *the request*, it belongs in the
service.

A good controller is boring and short. [StockController.java](../backend/src/main/java/com/stocklens/web/StockController.java)
delegates on the first statement of every method and has no `try`/`catch`
at all.

---

## 5. Service

**What it is.** The class holding the business rules — what the application
actually *does*, independent of how it was invoked.

```java
@Service
public class StockService {

    private final FinancialDataProvider provider;   // an interface, not a class
    private final StockLensProperties properties;
    private final Clock clock;

    public List<Stock> search(StockQuery query) {
        List<Stock> result = new ArrayList<>(currentSnapshot().stocks());
        if (query.maxPe() != null) {
            result.removeIf(s -> s.peRatio() == null || s.peRatio() > query.maxPe());
        }
        ...
    }
}
```

**Why separate it from the controller?**

- **It's testable.** `StockServiceTest` runs 27 tests with no server, no
  network, in milliseconds — impossible if the logic lived in the controller.
- **It's reusable.** If you later add a scheduled job or a CLI, both call the
  same service. The rules exist once.
- **It's readable.** "P/E filtering is inclusive and excludes nulls" is a
  business rule. Business rules should live somewhere you can find them.

**The tell that you got it right:** the service has no `import` of anything
HTTP — no `HttpServletRequest`, no `ResponseEntity`, no status codes. It throws
`StockNotFoundException`, not "404". Translating that exception into 404 is
someone else's job (§9).

---

## 6. Repository vs. Provider

These are the two ways a service gets data. Both sit *below* the service.

### Repository

The standard name for **"the thing that talks to the database."**

```java
public interface StockRepository {
    Optional<Stock> findByTicker(String ticker);
    List<Stock> findAll();
    Stock save(Stock stock);
}
```

**This project has no repository, because it has no database.** See
[ADR-001](adr/0001-no-database.md). If you later add PostgreSQL, a repository
is what you'd add, and it would slot in beside the provider without the
controller changing.

### Provider

**"Provider" is not a Spring keyword.** It's a role name this project chose for
*"the thing that fetches data from an external system."* You'll see the same
role called `Client`, `Gateway`, `Adapter`, or `ApiClient` in other codebases.
They all mean the same thing: **the boundary with something you don't control.**

```java
public interface FinancialDataProvider {
    Optional<Stock> fetchStock(String ticker);
}
```

One method. That's the entire contract between this app and the outside world.

**Why an interface and not just the Finnhub class directly?** Three reasons,
and the first is the big one:

1. **Testing.** `StockService` accepts anything implementing this interface. In
   tests, Mockito supplies a fake:

   ```java
   when(provider.fetchStock("MSFT")).thenReturn(Optional.of(msft));
   when(provider.fetchStock("BROKEN")).thenThrow(new FinancialDataException("HTTP 500"));
   ```

   You can now simulate a rate limit, a partial outage, or a company with a
   missing P/E — on demand, offline. Against the real API those states are
   impossible to arrange.

2. **Replaceability.** A second data source is a new class implementing the
   same interface. Nothing above it changes.

3. **Containment.** Finnhub's oddities — it answers unknown symbols with
   `200 OK` and `{}`, reports market cap in millions, doesn't document its
   field names — are all handled inside `FinnhubStockProvider` and invisible
   above it.

---

## 7. Domain model

**What it is.** The nouns of your problem, as Java types.

```java
public record Stock(String ticker, String name, String sector, Double price,
                    Double peRatio, Double marketCap, Double dividendYield,
                    Instant lastUpdated) { }
```

`Stock` is the domain model here. Notice what it *doesn't* have: no framework
annotations, no database annotations, no methods, no dependencies. It is pure
data, and every layer is allowed to use it.

You'll also see it called **entity** (usually when it maps to a database table)
or just **model**.

---

## 8. DTO — Data Transfer Object

**What it is.** An object whose only purpose is to carry data *across a
boundary*. Not a domain concept — a transport shape.

Three boundaries typically want their own DTO:

| Boundary | Purpose | In this project |
| --- | --- | --- |
| Request in | Shape of what the client sends | `StockQuery` |
| Response out | Shape of what the client receives | *none — `Stock` is sent directly* |
| External API in | Shape of what the vendor sends | `Profile`, `Quote`, `Metrics` |

### The inbound DTO: `StockQuery`

```java
public record StockQuery(String search, Double maxPe, Double minMarketCapBillions,
                         SortBy sortBy, Direction direction) { }
```

The controller receives five loose strings and numbers, validates them, and
bundles them into one typed object. The service then takes **one parameter**
instead of five, and by the time a `StockQuery` exists the values are already
valid. Adding a sixth filter later doesn't change the service's method
signature.

### The external DTOs: `Profile`, `Quote`, `Metrics`

```java
@JsonIgnoreProperties(ignoreUnknown = true)
record Profile(String name,
               @JsonProperty("finnhubIndustry") String industry,
               @JsonProperty("marketCapitalization") Double marketCapMillions) { }
```

These mirror **Finnhub's** JSON, not ours. They're declared package-private
inside `FinnhubStockProvider` — deliberately invisible to the rest of the app.
The provider maps them into a `Stock`, and `finnhubIndustry` never leaks
upward.

### The one this project deliberately skips

There is **no** `StockResponse` DTO. `StockController` returns `List<Stock>` —
the domain record itself — and Jackson serializes it straight to JSON.

That's a real trade-off, not an oversight:

- **Cost:** the domain model *is* the public API. Rename a field and you break
  every client. Add an internal field and you leak it.
- **Benefit:** no duplicate class and no mapping code, for a record that is
  already exactly the right shape.

For an 8-field read-only record with no secrets, the mapping layer would be
pure ceremony. **Split them the moment they need to differ** — when you must
hide a field, rename one for the API, or add a computed field the domain
doesn't have.

---

## 9. Exception handling as a layer

The service throws meaningful exceptions and doesn't think about HTTP:

```java
throw new StockNotFoundException(ticker);
```

One class, `GlobalExceptionHandler`, catches everything escaping any controller
and converts it to an HTTP response:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StockNotFoundException.class)
    public ProblemDetail stockNotFound(StockNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
```

**Why this is worth doing.** The alternative is `try`/`catch` in every
controller method, with error responses that look slightly different depending
on which branch produced them. Instead:

- The controller stays clean — literally zero `try`/`catch`.
- Every error has the same JSON shape.
- Adding a failure mode = one exception class + one method here.
- **The exception's *type* carries the meaning.** No error codes, no string
  parsing. `catch (StockNotFoundException e)` → 404.

---

## 10. Configuration

**`StockLensProperties`** turns `application.yml` into typed Java:

```yaml
stocklens:
  screener:
    cache-ttl: 10m
```
```java
@ConfigurationProperties(prefix = "stocklens")
public record StockLensProperties(Finnhub finnhub, Screener screener) { }
```

Now `properties.screener().cacheTtl()` is a real `Duration`, checked at
startup, instead of a string looked up by name in the middle of your logic.

**`AppConfig`** creates objects that Spring can't build on its own:

```java
@Configuration
public class AppConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean RestClient finnhubRestClient(StockLensProperties properties) { ... }
}
```

`Clock` is a JDK class — Spring has no reason to know you want one. `@Bean`
says "call this method, keep the result, hand it to anyone who asks for a
`Clock`." That indirection is exactly what lets tests substitute a fake clock
and test a 10-minute cache in microseconds ([ADR-004](adr/0004-inject-clock.md)).

---

## 11. Why the folder names

Packages have no behaviour. They're for humans. This project groups **by
layer**:

| Package | Holds | Why the name |
| --- | --- | --- |
| `web` | Controllers, exception handler | Everything that knows about HTTP. Also seen as `api`, `rest`, `controller`, `adapter.in`. |
| `service` | Business logic + its query/exception types | The application's actual behaviour. Also `domain.service`, `usecase`, `application`. |
| `provider` | The external-data interface | The boundary. Also `client`, `gateway`, `infrastructure`, `adapter.out`. |
| `provider.finnhub` | The Finnhub implementation | A sub-package per vendor, so a second one is a new folder. |
| `domain` | `Stock` | The nouns. Also `model`, `entity`. |
| `config` | Beans and settings | Wiring, not logic. |

**The point of the folder is that you can see a violation.** An import of
`provider` inside `web` is instantly visible in code review. If everything sat
in one package, nothing would stop the controller from calling Finnhub
directly, and nobody would notice.

### The alternative: package by feature

```
stock/     StockController, StockService, Stock
watchlist/ WatchlistController, WatchlistService, Watchlist
```

Better for large apps with many independent features, because everything about
one feature is in one folder. **Layer-based is the right call here** — there is
exactly one feature. With one feature, feature-packaging gives you one folder
containing everything, which tells you nothing.

---

## 12. Who calls whom

```
StockController.search(...)
  └─► StockService.search(StockQuery)
        └─► StockService.currentSnapshot()          [private]
              └─► StockService.refresh()            [private, synchronized]
                    └─► FinancialDataProvider.fetchStock("MSFT")   ← interface call
                          └─► FinnhubStockProvider.fetchStock(...) ← actual object
                                └─► RestClient → Finnhub
```

Two things to notice.

**The call goes one way.** No class here calls anything above it. `StockService`
never calls the controller; the provider never calls the service. If you ever
need an upward call, you've usually got the layers wrong.

**Line 5 is the interesting one.** `StockService` holds a field of type
`FinancialDataProvider` — an interface. It calls `fetchStock()` on it. It does
not know, and cannot find out, whether the object on the other end is a
`FinnhubStockProvider` or a Mockito fake. That single fact is what makes 27 of
the 57 tests possible.

---

## 13. Interfaces, inheritance, composition

A common misconception: that layers are built with **inheritance**. They are
not. `StockController` does not extend `StockService`. Layers are built with
**composition** — one object *holds a reference to* another.

| Relationship | Meaning | Example here |
| --- | --- | --- |
| **Composition** ("has-a") | An object holds another and calls it | `StockController` **has a** `StockService` |
| **Interface** ("can-do") | A promise about what methods exist | `FinnhubStockProvider` **implements** `FinancialDataProvider` |
| **Inheritance** ("is-a") | A class extends another, inheriting its code | `RateLimitedException` **extends** `FinancialDataException` |

### Composition is how layers connect

```java
public class StockController {
    private final StockService stockService;   // has-a
}
```

Not `extends`. The controller *uses* the service; it isn't a kind of service.

### Interfaces are how layers stay swappable

```java
public interface FinancialDataProvider { Optional<Stock> fetchStock(String ticker); }
public class FinnhubStockProvider implements FinancialDataProvider { ... }
```

The interface is the contract; the class is one way to keep it. `StockService`
depends on the contract, so any implementation will do.

### Inheritance appears exactly once, and it earns its place

The **entire** inheritance hierarchy in this codebase:

```
RuntimeException
├── FinancialDataException          (provider failed)
│   └── RateLimitedException        (provider failed, specifically: out of quota)
├── StockNotFoundException          (no such ticker)
└── DataUnavailableException        (nothing to serve at all)
```

That's it. Fifteen classes, one inheritance relationship between our own types.

And it does real work. Because `RateLimitedException` **is a**
`FinancialDataException`, a caller that doesn't care about the distinction
catches the parent and gets both. A caller that *does* care catches the child
first:

```java
try {
    provider.fetchStock(ticker).ifPresent(fresh::add);
}
catch (RateLimitedException e) {
    throw e;                      // out of quota → abandon the whole refresh
}
catch (FinancialDataException e) {
    log.warn("Skipping {}", ticker);   // one bad ticker → skip it, keep going
}
```

**Order matters and the compiler enforces it.** Put the parent first and the
child catch becomes unreachable — that's a compile error, not a silent bug.

**Rule of thumb:** reach for inheritance when subtypes genuinely form an
"is-a" family that callers want to treat both ways (exceptions are the classic
case). For everything else, use composition and interfaces. Deep inheritance
trees between services or controllers are a smell.

---

## 14. Dependency injection

Look at what `StockController` does **not** do:

```java
public StockController(StockService stockService) {
    this.stockService = stockService;      // received
}
```

versus:

```java
this.stockService = new StockService(new FinnhubStockProvider(...), ...);  // built
```

The first is **dependency injection**: the object declares what it needs, and
something else supplies it. The "something else" is Spring, and the pattern is
called **inversion of control** — you don't construct your collaborators, the
framework does.

### How Spring knows what to build

At startup, Spring scans for annotated classes and builds one instance of each,
called a **bean**:

| Annotation | Means |
| --- | --- |
| `@RestController` | A bean that handles HTTP |
| `@Service` | A bean holding business logic |
| `@Component` | A bean, unspecified role (`FinnhubStockProvider`) |
| `@Configuration` + `@Bean` | Beans built by hand, for types you don't own |

The annotations `@Service`, `@Component` and `@RestController` are functionally
near-identical to Spring — the distinction is **documentation for humans**.

Then it reads each constructor and supplies the arguments:

```
StockService needs a FinancialDataProvider
  → who implements it? FinnhubStockProvider (@Component)  → inject that
StockController needs a StockService
  → StockService (@Service)                               → inject that
```

This graph is built once, at startup. If something is missing, the app fails to
start with a clear message rather than throwing a `NullPointerException` later.

### Why bother

**Because the test can supply something different.**

```java
@WebMvcTest(StockController.class)
class StockControllerTest {
    @MockitoBean private StockService stockService;   // fake, not the real one
}
```

The controller is unchanged. It asked for a `StockService` and got one — a fake
that returns whatever the test wants. If the controller had used `new
StockService(...)` internally, no test could ever get between them.

**Always inject through the constructor**, as this codebase does. It lets every
field be `final`, makes dependencies impossible to overlook, and means the
object is fully formed the moment it exists.

---

## 15. One request, end to end

`GET /api/stocks?maxPe=30&sortBy=pe&order=asc`

| # | Where | What happens |
| --- | --- | --- |
| 1 | Tomcat | Receives the request, assigns a thread |
| 2 | Spring | Matches the URL to `StockController.search` |
| 3 | Spring | Binds `maxPe=30` → `Double 30.0`; `@Positive` passes |
| 4 | `StockController` | `SortBy.from("pe")` → `SortBy.PE`; builds a `StockQuery` |
| 5 | `StockService` | Cache warm and unexpired? Use it, skip to 9 |
| 6 | `StockService` | Cold/expired → `refresh()` loops the 18 tickers |
| 7 | `FinnhubStockProvider` | 3 HTTP calls per ticker; JSON → `Profile`/`Quote`/`Metrics` → `Stock` |
| 8 | `StockService` | Stores the snapshot with a timestamp from the injected `Clock` |
| 9 | `StockService` | Filters `peRatio <= 30`, sorts ascending, nulls last, ties on ticker |
| 10 | Spring | Jackson serializes `List<Stock>` → JSON |
| 11 | Tomcat | `200 OK` + body |

If anything throws at steps 4–9, control jumps straight to
`GlobalExceptionHandler`, which returns `400`, `404` or `503`. No intermediate
layer needs a `catch`.

---

## 16. Why this shape makes testing possible

The layers aren't decoration — they're what the 57 tests are built on. Each
test targets one layer and fakes everything below it:

| Test | Real | Faked | Result |
| --- | --- | --- | --- |
| `StockServiceTest` (27) | The service | Provider (Mockito), `Clock` | Business rules, no network |
| `FinnhubStockProviderTest` (10) | The provider | Finnhub (local `MockWebServer`) | HTTP 500, 429, timeouts, bad JSON |
| `StockControllerTest` (11) | Web layer only | The service | Validation, JSON, status codes |
| `StockScreenerIntegrationTest` (3) | Everything | Only the provider | The layers really connect |
| `StocklensApplicationTests` (1) | Everything | — | The app starts and wires up |
| `OpenApiDocsTest` (5) | Everything | — | The generated docs are correct |

Notice the pattern: **each seam in the architecture is a place a test can cut.**
No seams, no unit tests. This is the practical payoff of the whole structure —
57 tests that run in about five seconds without touching the network.

---

## 17. Rules of thumb

**Where does this code go?**

| If it… | It belongs in |
| --- | --- |
| Reads a query parameter or sets a status code | `web` |
| Decides *what the answer should be* | `service` |
| Sends a request to someone else's system | `provider` |
| Is just data | `domain` |
| Is a setting or object wiring | `config` |

**Smells**

- A controller with business logic → move it down
- A service importing `HttpServletRequest` or returning `ResponseEntity` → the layers leaked
- A service importing something Finnhub-specific → you skipped the interface
- `new` on a collaborator inside a class → you just made it untestable
- `catch (Exception e) { }` → evidence destroyed
- A change that forces edits in all four layers at once → the abstraction is wrong

---

## 18. Glossary

| Term | Meaning |
| --- | --- |
| **Bean** | An object Spring created and manages |
| **Controller** | Maps URLs to methods; the only HTTP-aware class |
| **Service** | Business logic, framework-independent |
| **Repository** | Talks to the database (none here — no database) |
| **Provider / Client / Gateway** | Talks to an external system |
| **DTO** | An object that carries data across a boundary |
| **Domain model / Entity** | The nouns of the problem, as types |
| **Dependency injection** | Being handed your collaborators instead of constructing them |
| **Inversion of control** | The framework calls you; you don't call it |
| **Composition** | "has-a" — an object holds another |
| **Inheritance** | "is-a" — a class extends another |
| **Interface** | A contract: method signatures with no implementation |
| **Serialization** | Java object → JSON (Jackson does this) |
| **Mock / stub** | A fake collaborator supplied in a test |
| **Layer** | A group of classes with one job, depending only downward |

---

## Where to go next

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — what each component in *this* app does
- [`adr/`](adr/) — why each design decision was made, with the trade-offs
- [`API.md`](API.md) — the HTTP contract from a client's point of view
