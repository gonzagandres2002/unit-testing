# Testing

How this project is tested, and the concepts behind it. Every example is real
code from `backend/src/test/`.

**Read in order:** §1–2 for the vocabulary and the AAA pattern. §3 for unit
testing (the bulk of it). §4 integration testing. §5 performance. §6 how
Gradle runs all of this. §7 coverage.

**Quick commands**

```bash
cd backend
./gradlew test                              # 66 tests + coverage report
./gradlew test --tests '*StockServiceTest*' # one class
./gradlew test --tests '*Performance*'      # just the performance tests
open build/reports/tests/test/index.html            # test results
open build/reports/jacoco/test/html/index.html      # coverage
```

**The 66 tests at a glance**

| Class | Count | Kind | AAA comments |
| --- | --- | --- | --- |
| [`StockServiceTest`](../backend/src/test/java/com/stocklens/service/StockServiceTest.java) | 29 | Unit | ✅ |
| [`FinnhubStockProviderTest`](../backend/src/test/java/com/stocklens/provider/finnhub/FinnhubStockProviderTest.java) | 10 | Unit | ✅ |
| [`StockControllerTest`](../backend/src/test/java/com/stocklens/web/StockControllerTest.java) | 13 | Integration (`@WebMvcTest` slice) | ✅ |
| [`StockScreenerIntegrationTest`](../backend/src/test/java/com/stocklens/StockScreenerIntegrationTest.java) | 3 | Integration (full `@SpringBootTest`) | ✅ |
| [`OpenApiDocsTest`](../backend/src/test/java/com/stocklens/web/OpenApiDocsTest.java) | 5 | Integration (full `@SpringBootTest`) | — |
| [`StocklensApplicationTests`](../backend/src/test/java/com/stocklens/StocklensApplicationTests.java) | 1 | Smoke test | — |
| [`StockServicePerformanceTest`](../backend/src/test/java/com/stocklens/service/StockServicePerformanceTest.java) | 3 | **Performance**, service level | ✅ |
| [`StockScreenerPerformanceTest`](../backend/src/test/java/com/stocklens/StockScreenerPerformanceTest.java) | 2 | **Performance**, full HTTP stack | ✅ |

`OpenApiDocsTest` and `StocklensApplicationTests` pre-date the AAA pass done
in this project's history and are intentionally left as-is — they're one or
two lines each, where the phases add no clarity.

---

## 1. What a test is

A method that runs your code with known inputs and fails the build if the
result is wrong.

```java
@Test
void findsExistingCompanyByTickerIgnoringCase() {
    serviceWith(MSFT, GOOGL, AAPL);
    assertThat(tickers(service.search(query("aapl", null, null))))
        .containsExactly("AAPL");
}
```

That's the whole idea. `@Test` marks it, JUnit runs it, an assertion decides
pass or fail. If nothing throws, it passes.

**Why bother.** Not to prove the code works today — you could check that by
hand. It's to prove it *still* works after tomorrow's change. A test suite is
a machine that answers "did I just break something?" in five seconds instead
of twenty minutes of clicking.

### The libraries here

| Library | Version | Job |
| --- | --- | --- |
| **JUnit Jupiter** | 6.0.3 | Finds and runs tests (`@Test`, `@Nested`, `@BeforeEach`) |
| **AssertJ** | 3.27.7 | Readable assertions (`assertThat(x).isEqualTo(y)`) |
| **Mockito** | 5.23.0 | Fake collaborators (`when(...).thenReturn(...)`) |
| **MockWebServer** | 4.12.0 | A real HTTP server on localhost, for testing the API client |
| **Spring Boot Test** | 4.1.0 | `@SpringBootTest`, `@WebMvcTest`, `MockMvc` |

All arrive through `spring-boot-starter-webmvc-test` except MockWebServer.
You don't pick their versions — the Spring Boot BOM does.

---

## 2. The AAA pattern

**Arrange, Act, Assert.** Every test has three phases, in that order:

| Phase | Question | Typical code |
| --- | --- | --- |
| **Arrange** | What's the starting state? | Build objects, stub collaborators |
| **Act** | What am I testing? | Call the one method under test |
| **Assert** | What should have happened? | Check the result |

You'll also see it called **Given–When–Then**. Same three phases, different
words — Given/When/Then comes from BDD and is common in test *names*.

### A textbook example from this project

```java
@Test
void expiredCacheIsRefreshedFromProvider() {
    // ARRANGE — a service over two tickers, provider stubbed
    serviceWith(MSFT, GOOGL);
    service.search(query(null, null, null));   // prime the cache
    clock.advance(TTL.plusSeconds(1));         // move past the TTL

    // ACT — the call under test
    service.search(query(null, null, null));

    // ASSERT — it went back to the provider (2 tickers x 2 refreshes)
    verify(provider, times(4)).fetchStock(anyString());
}
```

### Why the order matters

**One Act per test.** If a test calls three different methods, a failure
doesn't tell you which one broke. The name should describe one behaviour.

**Assert after Act, never during.** Assertions inside the arrange phase are
testing your setup, not your code.

**No logic in the test.** No `if`, no loops over cases, no computing the
expected value with the same formula the code uses. Write the expected value
as a literal:

```java
// GOOD — a human decided the answer is 3.1e12
assertThat(stock.marketCap()).isEqualTo(3.1e12);

// BAD — if the conversion is wrong, the test is wrong the same way
assertThat(stock.marketCap()).isEqualTo(profile.marketCapMillions() * 1_000_000d);
```

### AAA when the Act throws

Testing an exception merges Act and Assert, because the call has to be inside
the assertion:

```java
@Test
void httpErrorBecomesFinancialDataException() {
    server.enqueue(new MockResponse().setResponseCode(500));         // Arrange

    assertThatThrownBy(() -> provider.fetchStock("MSFT"))            // Act + Assert
        .isInstanceOf(FinancialDataException.class)
        .hasMessageContaining("HTTP 500");
}
```

This is normal and correct. `assertThatThrownBy` (AssertJ) and
`assertThrows` (JUnit) both exist for it.

### Where the Arrange phase goes when it repeats

Most tests here share setup. Rather than repeating it, it moves into a helper:

```java
/** Configures a universe of the given stocks and stubs the provider with them. */
private StockService serviceWith(Stock... stocks) {
    for (Stock stock : stocks) {
        when(provider.fetchStock(stock.ticker())).thenReturn(Optional.of(stock));
    }
    return serviceForTickers(Arrays.stream(stocks).map(Stock::ticker).toList());
}
```

Now the Arrange phase of a test is one readable line. The trade-off: setup is
no longer visible in the test body, so the helper must be small and obviously
named. `@BeforeEach` does the same job for setup that is identical everywhere.

### Where to see it in this codebase

Six of the eight test classes mark all three phases with `// Arrange`,
`// Act` and `// Assert` comments (`// Act & Assert` where the operation and
its verification are necessarily one call, as with `assertThatThrownBy`):

- [`StockServiceTest`](../backend/src/test/java/com/stocklens/service/StockServiceTest.java) — the clearest set of examples; 29 tests, every phase separated onto its own line.
- [`FinnhubStockProviderTest`](../backend/src/test/java/com/stocklens/provider/finnhub/FinnhubStockProviderTest.java) — Arrange is enqueuing MockWebServer responses.
- [`StockControllerTest`](../backend/src/test/java/com/stocklens/web/StockControllerTest.java) — Act captures the `MockMvc` `ResultActions`/`ArgumentCaptor` result *before* the Assert block chains `.andExpect(...)`/`assertThat(...)` on it, instead of fusing the call and the check into one statement.
- [`StockScreenerIntegrationTest`](../backend/src/test/java/com/stocklens/StockScreenerIntegrationTest.java) — `searchAndDetailEndpointWorkTogether` has two Act/Assert pairs, labelled, because that test deliberately checks a two-request sequence.
- [`StockServicePerformanceTest`](../backend/src/test/java/com/stocklens/service/StockServicePerformanceTest.java) and [`StockScreenerPerformanceTest`](../backend/src/test/java/com/stocklens/StockScreenerPerformanceTest.java) — see §5; the Act phase is what's wrapped in `assertTimeout`.

Where there's nothing to arrange — request-validation tests that never reach
the mocked service — the comment stays and says so explicitly
(`// Arrange — no stubbing needed; validation happens before the service is
called`) rather than being silently dropped, so the three-phase shape is
never ambiguous. `OpenApiDocsTest` and `StocklensApplicationTests` are the
two exceptions (see the "66 tests at a glance" table above): each test body
is one or two lines, short enough that phase comments would be noise rather
than structure.

---

## 3. Unit testing

### What makes a test a unit test

It exercises **one class** with everything it depends on replaced by fakes.
No network, no database, no framework, no clock, no filesystem.

The practical test: a unit test is **fast** (milliseconds), **deterministic**
(same result every run, forever), and **isolated** (its result doesn't depend
on any other test).

`StockServiceTest` is the model: 29 tests, real `StockService`, fake provider,
fake clock, no Spring context at all.

```java
@ExtendWith(MockitoExtension.class)   // enables @Mock, no Spring involved
class StockServiceTest {

    @Mock
    private FinancialDataProvider provider;      // fake

    private final MutableClock clock = new MutableClock();   // fake

    private StockService service;                // real — the thing under test
}
```

### Test doubles

"Mock" is used loosely to mean any fake. The precise vocabulary:

| Kind | What it does | Example here |
| --- | --- | --- |
| **Dummy** | Passed to satisfy a signature, never used | `new StockLensProperties(null, screener)` |
| **Stub** | Returns canned answers | `when(provider.fetchStock("MSFT")).thenReturn(...)` |
| **Mock** | A stub you also assert *was called* | `verify(provider, times(1)).fetchStock(...)` |
| **Fake** | A real but simplified implementation | `MutableClock`, `MockWebServer` |
| **Spy** | Wraps a real object, records calls | *not used here* |

The distinction that matters: a **stub** helps you arrange, a **mock** is part
of your assertion. Verifying calls couples the test to *how* the code works,
so use it only when the interaction **is** the behaviour:

```java
// The requirement IS "stop calling after a 429" — so verifying the call count
// is the only way to express it.
verify(provider, times(1)).fetchStock(anyString());
```

### Mockito in four calls

```java
when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT));   // stub
when(provider.fetchStock(any())).thenThrow(new RateLimitedException("429"));  // stub a throw
verify(provider, times(4)).fetchStock(anyString());                // assert it was called
ArgumentCaptor<StockQuery> captor = ArgumentCaptor.forClass(StockQuery.class);
verify(stockService).search(captor.capture());                     // capture the argument
```

`ArgumentCaptor` answers "what exactly was passed?" — used in
`StockControllerTest` to prove the controller translated `?sortBy=pe` into
`SortBy.PE` without depending on the service at all.

### Determinism: the injected clock

The rule "a test must give the same answer every time" is why `Clock` is a
constructor parameter and not `Instant.now()`:

```java
private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-08-17T12:00:00Z");
    void advance(Duration duration) { instant = instant.plus(duration); }
    @Override public Instant instant() { return instant; }
}
```

A 10-minute TTL is then tested in microseconds. The alternative —
`Thread.sleep` with a shortened TTL — is slow *and* flaky, because it fails
whenever the machine is briefly busy. **Any test containing `sleep` is a bug
waiting to happen.**

### Naming

The name is the specification. It should say what holds, so a failure report
is readable without opening the file:

```
maxPeKeepsOnlyCheaperCompaniesAndTreatsBoundaryAsInclusive
companiesMissingTheSortedMetricGoLastInBothDirections
rateLimitWithoutCacheMeansDataUnavailable
missingApiKeyFailsFastWithoutCallingTheApi
```

Not `testSearch1`, `testSearch2`. If you can't name it in one clause, the test
is probably doing two things.

### Grouping with `@Nested`

`StockServiceTest` has 29 tests split into six inner classes:

```java
@Nested class Search { ... }              // 5 tests
@Nested class Filtering { ... }           // 6 tests
@Nested class Sorting { ... }             // 6 tests
@Nested class CachingAndResilience { ... } // 8 tests
@Nested class SingleStockLookup { ... }   // 2 tests
@Nested class QueryParsing { ... }        // 2 tests
```

Results are then reported grouped, and each group can have its own setup.

### What to test: boundaries, not the happy path

The happy path is the easy case and rarely where bugs live. The valuable
tests target edges:

| Edge | Test |
| --- | --- |
| Exactly on the limit | `maxPe=29.5` against a P/E of exactly 29.5 |
| Zero | `minMarketCap=0` |
| Negative | A company with P/E `-8.2` |
| Absent data | A stock with `null` P/E and `null` market cap |
| Empty input | Blank search string |
| Huge value | `minMarketCap=1e12` → empty result |
| Failure | Provider throws on one ticker, on all tickers, or rate-limits |

That list is why `StockServiceTest` is 29 tests for a class with two public
methods.

---

## 4. Integration testing

### The difference

| | Unit test | Integration test |
| --- | --- | --- |
| Scope | One class | Several, wired together |
| Collaborators | All faked | Real, except external systems |
| Speed | Milliseconds | Hundreds of ms to seconds |
| Finds | Logic errors | **Wiring** errors |
| Count | Many | Few |

A unit test proves each piece is right. An integration test proves the pieces
are **connected** right. Both can pass individually while the app is broken —
that's precisely the gap integration tests close.

### The pyramid

```
        ╱  few  ╲        integration — slow, broad, catches wiring
      ╱─────────╲
    ╱    many    ╲       unit — fast, narrow, catches logic
  ╱───────────────╲
```

This project: **42 pure unit tests** (`StockServiceTest`,
`FinnhubStockProviderTest`, `StockServicePerformanceTest` — no Spring at all)
and **24 that boot some or all of Spring** — 13 in the `@WebMvcTest` slice
(`StockControllerTest`), 11 across full-context `@SpringBootTest` classes
(`StockScreenerIntegrationTest`, `StocklensApplicationTests`,
`StockScreenerPerformanceTest`, `OpenApiDocsTest`). Deliberate. Each
`@SpringBootTest` class pays for a Spring context, so those tests cover
connections, not permutations.

### Slice tests — the middle tier

`@WebMvcTest` starts *only* the web layer. Real JSON serialization, real
validation, real exception handler — but no service, no provider, no cache.

```java
@WebMvcTest(StockController.class)
class StockControllerTest {

    @Autowired  private MockMvc mockMvc;
    @MockitoBean private StockService stockService;   // replaced with a fake

    @Test
    void nonNumericFilterValueIsRejected() throws Exception {
        mockMvc.perform(get("/api/stocks").param("maxPe", "cheap"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Parameter 'maxPe' has an invalid value"));
    }
}
```

`MockMvc` sends a fake HTTP request through the real Spring machinery without
opening a socket. `@MockitoBean` swaps a bean in the context for a Mockito
fake.

This is the sweet spot for testing an HTTP contract: it catches a wrong status
code or a renamed JSON field, without the cost of booting everything.

### Full integration

```java
@SpringBootTest(properties = "stocklens.screener.tickers=MSFT,GOOGL,AAPL")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StockScreenerIntegrationTest {

    @MockitoBean private FinancialDataProvider provider;   // ONLY the outside world is faked

    @Test
    void screensFiltersAndSortsThroughTheFullStack() throws Exception {
        givenHealthyProvider();

        mockMvc.perform(get("/api/stocks").param("maxPe", "30")
                        .param("sortBy", "pe").param("order", "asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].ticker").value("GOOGL"));
    }
}
```

Everything real: controller, validation, service, cache, filtering, sorting,
JSON. Only `FinancialDataProvider` is faked, because tests must never call
Finnhub — that would be slow, flaky, and would burn API quota.

**Three details worth copying:**

1. **`properties = "..."`** overrides config for the test, shrinking the
   universe from 18 tickers to 3.
2. **`@DirtiesContext(AFTER_EACH_TEST_METHOD)`** rebuilds the context between
   tests. Required here because `StockService`'s cache is **stateful** —
   without it, a snapshot cached by test 1 leaks into test 3 and the
   cold-cache `503` test fails. **This is the classic integration-test trap:
   shared state between tests.**
3. **Only three tests.** They cover the paths that unit tests structurally
   cannot: does the whole chain connect, and does a cold-cache outage really
   produce a 503 over HTTP.

### The smoke test

```java
@SpringBootTest
class StocklensApplicationTests {
    @Test void contextLoads() { }
}
```

Empty body, and one of the highest-value tests in the project. It fails if any
bean can't be created — a missing dependency, a bad `@Value`, a duplicate
bean. It catches "the app won't start" before anything else runs.

### What *not* to do in an integration test

- Don't re-test business rules. That `maxPe` is inclusive is a unit test's
  job. Duplicating it here just makes the suite slow.
- Don't hit real external services. Ever.
- Don't depend on test execution order.

---

## 5. Performance testing

Different question. Correctness tests ask *"is the answer right?"*.
Performance tests ask *"how fast, and how does it degrade under load?"*.

### The tempting mistake

```java
@Test
void searchIsFast() {
    assertTimeout(Duration.ofMillis(50), () -> service.search(query));
}
```

**Be careful with this.** It looks like a performance test and can easily
become a flakiness generator:

- The JVM is slow for the first few thousand runs (JIT hasn't compiled yet),
  so it measures warm-up, not steady state.
- A garbage collection pause, a busy CI machine, or a laptop on battery
  changes the number.
- A tight budget (50ms) is arbitrary. When it fails, nobody knows if the code
  regressed or the machine was busy, so the usual fix is to raise the limit
  until it stops failing — at which point it asserts nothing.

That is a real risk, not a hypothetical one — it's exactly the shape of the
two performance test classes this project actually has. They stay useful
because of *how* the budget is chosen (see below), not because the risk goes
away.

### The three real levels — and where JUnit's `assertTimeout` fits

| Level | Question | Tool |
| --- | --- | --- |
| **Regression guard** | Did this get *dramatically* slower (10×, not 10%)? | **JUnit 5 `assertTimeout`** — what this project uses |
| **Micro-benchmark** | How long does this method take, precisely? | **JMH** |
| **Load test** | What happens at 500 requests/second? | **k6**, **Gatling**, **JMeter** |
| **Profiling** | Where is the time actually going? | **async-profiler**, JFR, IntelliJ profiler |

**JMH** (Java Microbenchmark Harness) is the only credible way to *measure*
Java code precisely. It handles JVM warm-up, runs many iterations, prevents
the optimiser from deleting your benchmark as dead code, and reports
variance. It lives in a separate source set, not in `src/test/java`, because
a benchmark run takes minutes and must not be part of `./gradlew test`. This
project does not have a JMH suite.

**Load testing** targets the running API over HTTP, not classes. A k6 script
against `/api/stocks` would answer the question that actually matters for a
web service: what does p95 latency look like at N concurrent users. This
project does not have one of those either.

What this project *does* have is the level in between "no performance
testing" and "a real benchmark": a cheap, coarse-grained **regression guard**
that runs on every `./gradlew test`, catches an accidental algorithmic
regression (e.g. an `O(n)` lookup turning into `O(n²)`), and is *deliberately*
not trying to measure precise timings — because precise timing is exactly
what makes `assertTimeout` flaky.

### `StockServicePerformanceTest` — service level, no Spring

```java
@Test
void unfilteredSearchOverLargeUniverseCompletesWithinBudget() {
    // Act & Assert
    List<Stock> results = assertTimeout(SEARCH_BUDGET,
            () -> service.search(new StockQuery(null, null, null, SortBy.MARKET_CAP, Direction.DESC)));

    assertThat(results).hasSize(LARGE_UNIVERSE_SIZE);
}
```

Runs `StockService.search()` — the actual search/filter/sort pipeline —
against a **10,000-stock synthetic universe**, roughly 550× production's 18
tickers. Three tests:

1. Unfiltered search stays under budget.
2. Filtered + sorted search stays under budget.
3. A cached (second) search is not slower than 2× the cold (first) search —
   a regression test for "did someone accidentally bypass the cache".

### `StockScreenerPerformanceTest` — the full HTTP stack

Same idea, one layer up: `MockMvc` → `StockController` → `StockService` →
mocked `FinancialDataProvider`, the same mocking approach as
`StockScreenerIntegrationTest` (§4) — no test here ever calls the real
Finnhub API. A 1,000-ticker universe is injected via `@DynamicPropertySource`
because `@SpringBootTest(properties = ...)` only accepts compile-time
constants, not a generated list.

This adds what the service-level test structurally cannot see: request
routing, parameter validation, and **JSON serialization** of a large
response body.

### How the flakiness risk is actually managed here

The critique above is real, so both classes are deliberately built to blunt
it rather than ignore it:

| Risk | How it's mitigated |
| --- | --- |
| JIT warm-up skews the first run | Not fully solved — accepted as noise absorbed by a generous budget (500ms–2s for work that normally takes single-digit milliseconds), not a tight one |
| A slow CI box makes the test flaky | Budgets are ~50–100× the expected time, wide enough to survive a busy machine, tight enough to still catch a real `O(n²)` regression |
| Mock setup cost pollutes the measurement | `StockServicePerformanceTest` uses a hand-written in-memory `FinancialDataProvider` (a `Map` lookup) instead of 10,000 individual Mockito `when(...)` stubs; `StockScreenerPerformanceTest` uses one `thenAnswer(...)` that computes a stock on the fly instead of 1,000 stubs |
| Non-determinism from random data | Both universes are generated with a fixed-seed `Random(42)` — the same input every run, forever |
| "Just raise the limit until it's green" | The point of these tests is *not* to assert a tight number — a 10× regression should still fail even with room to spare; that is a coarser, more honest goal than a micro-benchmark's |

**The honest framing:** these are not a substitute for JMH or a load test.
They are a five-minute investment that fails the build if someone
accidentally makes the screener quadratic — which is strictly better than
finding that out from a slow production endpoint, and costs nothing beyond
`./gradlew test`, which already runs on every change.

### What a *real* performance investigation would still measure

If this project needed to go further, the three things worth measuring are
architectural, not algorithmic — and none of them are covered by the two
classes above, because both mock away the network:

1. **Cold vs. warm cache.** A warm request is a field read plus a filter — 
   microseconds. A cold request makes **54 serial HTTP calls** to Finnhub.
   That gap is the dominant performance fact of the system, and it's a
   property of [ADR-001](adr/0001-no-database.md), not of any method.
2. **The serial refresh.** 54 sequential calls at ~100ms each is ~5 seconds,
   and it happens **on the request thread** that triggered the refresh. That
   user waits. Parallelising or refreshing in the background is the obvious
   optimisation — and a load test is how you'd justify it.
3. **Timeout behaviour under a slow provider.** Connect 3s + read 5s per call
   bounds the damage; a load test with a deliberately slow mock would show
   whether the thread pool survives.

That would need JMH or k6 against a running instance, not more JUnit tests —
which is exactly why this project doesn't fake it with `assertTimeout` on
something network-shaped.

---

## 6. How Gradle runs all of this

This project uses **Gradle** (via the wrapper, `./gradlew`), not Maven — no
`pom.xml` anywhere, no `mvn` commands. Everything below lives in
[`backend/build.gradle`](../backend/build.gradle).

### Plugins

```groovy
plugins {
    id 'java'
    id 'jacoco'
    id 'org.springframework.boot' version '4.1.0'
    id 'io.spring.dependency-management' version '1.1.7'
}
```

| Plugin | What it adds |
| --- | --- |
| `java` | Compiles `src/main`/`src/test`, and — the relevant part here — the built-in **`test` task**, which is a `Test` task type Gradle understands how to run, report on, and cache. |
| `jacoco` | Instruments the JVM during `test` to produce the coverage report (§7). |
| `org.springframework.boot` | Adds `bootRun`, builds an executable jar, and (with the dependency-management plugin) pins every Spring dependency to versions that are tested together — no version numbers on `spring-boot-starter-*` in the dependency block. |

### Test dependencies

```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
testImplementation 'org.springframework.boot:spring-boot-starter-validation-test'
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

`spring-boot-starter-webmvc-test` is one line that pulls in JUnit Jupiter,
AssertJ, Mockito, `MockMvc` and Spring's test context support — the whole
table in §1 arrives through it, except MockWebServer, added separately
because it's not a Spring dependency. `junit-platform-launcher` is what lets
Gradle actually *discover and run* JUnit 5 tests, not just compile them.

### The `test` task

```groovy
tasks.named('test') {
    useJUnitPlatform()
    finalizedBy tasks.named('jacocoTestReport')
}
```

`useJUnitPlatform()` is the one line that switches Gradle's test runner from
JUnit 4 to **JUnit 5** (the JUnit Platform) — without it, `@Test` methods
from `org.junit.jupiter.api` would silently not run at all. `finalizedBy`
means coverage is regenerated after *every* `./gradlew test`, successful or
not, so the report shown in §7 is never stale.

Running `./gradlew test`:

1. Compiles `src/main` and `src/test` (`compileJava`, `compileTestJava`).
2. Discovers every class under `src/test/java` with `@Test` methods —
   including all eight classes in the table above, with no separate
   configuration needed to include the two performance ones. There is no
   "performance" test source set here; they're plain JUnit 5 tests that
   happen to call `assertTimeout`.
3. Runs them (in parallel across classes, sequentially within a class, by
   default) and writes:
   - `build/test-results/test/*.xml` — machine-readable, one file per class
     (what generated the counts in the table above).
   - `build/reports/tests/test/index.html` — human-readable results.
4. Triggers `jacocoTestReport`.

### Useful task invocations

```bash
./gradlew test                                  # everything
./gradlew test --tests '*StockServiceTest*'      # one class
./gradlew test --tests '*Performance*'           # just the two performance classes
./gradlew test --tests '*.CachingAndResilience'  # one @Nested group
./gradlew test --rerun                           # ignore Gradle's up-to-date cache
./gradlew bootRun                                # run the app for real
./gradlew exportOpenApiSpec                      # regenerate docs/openapi.yaml (see docs/API.md)
```

**`--rerun` matters for this project specifically.** Gradle skips `test`
with `UP-TO-DATE` if none of the inputs changed since the last run — normally
a good thing, but the environment-variable input (`FINNHUB_API_KEY`) isn't
part of that input set, so if you're re-running to double-check something
environment-dependent, `--rerun` forces it to actually execute again instead
of replaying the cached result.

---

## 7. Coverage

**Coverage = what percentage of your code ran while the tests ran.**

Note what that does *not* say: nothing about whether the results were checked.
A test with no assertions produces coverage.

### Running it

Configured with the `jacoco` plugin in [`build.gradle`](../backend/build.gradle).
Every test run refreshes the report (`finalizedBy jacocoTestReport`):

```bash
cd backend
./gradlew test
open build/reports/jacoco/test/html/index.html
```

The HTML report is colour-coded per line:

| Colour | Meaning |
| --- | --- |
| 🟩 green | Executed |
| 🟨 yellow | **Partially covered** — an `if` where only one outcome happened |
| 🟥 red | Never executed |

**Yellow is the interesting colour.** It marks a branch you never exercised.

### The metrics

| Metric | Meaning | Here |
| --- | --- | --- |
| **Instruction** | Bytecode instructions executed | **97.5%** |
| **Branch** | `if`/`switch`/`?:` outcomes taken | **86.5%** |
| **Line** | Source lines touched | 97.0% |
| **Method** | Methods called at least once | 100% |

**Branch coverage is the number to watch.** Line coverage counts a line as
covered if it ran at all; branch coverage asks whether *both* outcomes
happened. `if (a || b)` on one line can be "100% line covered" with three of
four branches untested. That's why 97.5% instruction and 86.5% branch coexist
here — and the branch number is the honest one.

### What the report found in this project

Coverage is a **map of where you haven't looked**. Reading the yellow and red
in this codebase surfaced four genuine gaps:

| Location | Untested | Real risk? |
| --- | --- | --- |
| `StockService:151` | The double-check inside `synchronized refresh()` — "another thread refreshed while we waited" | **Yes.** All 66 tests are single-threaded, so no test has ever exercised the concurrency guard. |
| `FinnhubStockProvider:103` | `catch (ResourceAccessException e)` | **Yes, and it's instructive.** There *is* a timeout test — but it exits through the *other* catch, via `hasIoCause()`. Coverage proves the first path is never taken by any test. |
| `FinnhubStockProvider:128` | `marketCapMillions() == null` | Yes — a profile with no market cap is realistic and untested. |
| `FinnhubStockProvider:140` | `firstMetric` returning null when the metric map exists but has none of the known keys | Yes — likely, given Finnhub doesn't document those field names. |

None of those are visible by reading the tests. That is what coverage is for.

### Excluded from the numbers

```groovy
exclude: [
    'com/stocklens/StocklensApplication.class',
    'com/stocklens/config/**',
]
```

`main()` and the `@Configuration` classes are framework wiring with no branches
to get wrong. Including them would inflate the percentage with code whose only
meaningful test is "the app starts" — which `contextLoads` already covers.
**Exclusions should be justified, not used to hide untested logic.**

### Why 100% is the wrong goal

Coverage is a **necessary but not sufficient** condition. Code that never runs
is definitely untested; code that runs is only *maybe* tested.

```java
@Test
void uselessButFullyCovering() {
    service.search(query(null, null, null));   // 100% coverage of search()
}                                              // ...asserts nothing
```

That test can never fail. It contributes coverage and zero confidence.

Chasing the last few percent also has a real cost: you end up writing
contorted tests for defensive branches that can't occur, and those tests
cement implementation details, making refactoring harder. **Use coverage to
find the gaps you *forgot*, then judge each one.** A deliberate decision not
to test the concurrency double-check is fine. Not *knowing* it was untested
is not.

Useful targets: **~80% branch coverage** as a floor, with 100% on the classes
holding real business rules. This project sits at 92% branch on `StockService`
and 76.5% on `FinnhubStockProvider` — which correctly says the screening logic
is well covered and the error-handling paths of the API client are the weaker
half.

### Enforcing a minimum

JaCoCo can fail the build below a threshold:

```groovy
tasks.named('jacocoTestCoverageVerification') {
	violationRules {
		rule {
			limit { counter = 'BRANCH'; minimum = 0.80 }
		}
	}
}
tasks.named('check') { dependsOn 'jacocoTestCoverageVerification' }
```

Not enabled here — worth adding if this were a team project, where it stops
coverage silently eroding.

### Beyond coverage: mutation testing

Coverage asks "did this line run?". **Mutation testing** asks the better
question: "if I broke this line, would a test notice?"

A tool like **PIT** deliberately introduces bugs — flips `>` to `>=`, replaces
a return with `null` — reruns the tests, and reports which mutations
**survived**. A surviving mutation is proof of a missing assertion, which is
exactly the blind spot coverage cannot see. Given this project's
boundary-heavy filters (`maxPe` inclusive vs exclusive is a `>` vs `>=`
away), it would be a genuinely good fit.

Not currently wired into this build.

---

## 8. Rules of thumb

- **One behaviour per test**, named as a sentence about that behaviour.
- **Assert on values, not on implementation** — unless the interaction *is*
  the requirement (`verify(times(1))` after a rate limit).
- **No `sleep`, no real clock, no network, no random, no dependence on test
  order.** Any of those is a future flaky failure.
- **Test the boundaries**, not the happy path.
- **A failing test should tell you what broke from its name alone.**
- **Write the test first when fixing a bug** — it should fail, then pass. That
  proves the test actually detects the bug.
- **Coverage finds gaps; it does not measure quality.**

---

## See also

- [`ARCHITECTURE.md`](ARCHITECTURE.md#testing-architecture) — the test tiers in context
- [`FUNDAMENTALS.md`](FUNDAMENTALS.md#16-why-this-shape-makes-testing-possible) — why the layering is what makes unit testing possible
- [`adr/0004-inject-clock.md`](adr/0004-inject-clock.md) — the injected-clock decision, in full
