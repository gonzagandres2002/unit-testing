# Testing

How this project is tested, and the concepts behind it. Every example is real
code from `backend/src/test/`.

**Read in order:** §1–2 for the vocabulary and the AAA pattern. §3 for unit
testing (the bulk of it). §4 integration testing. §5 performance. §6 how
Gradle runs all of this. §7 coverage.

**Quick commands**

```bash
cd backend
./gradlew test                              # 20 tests + coverage report
./gradlew test --tests '*StockServiceTest*' # one class
open build/reports/tests/test/index.html            # test results
open build/reports/jacoco/test/html/index.html      # coverage
```

**The 20 tests at a glance**

| Class | Count | Kind | AAA comments |
| --- | --- | --- | --- |
| [`StockServiceTest`](../backend/src/test/java/com/stocklens/service/StockServiceTest.java) | 10 | Unit | ✅ |
| [`StockScreenerIntegrationTest`](../backend/src/test/java/com/stocklens/StockScreenerIntegrationTest.java) | 10 | Integration (full `@SpringBootTest`) | ✅ |

Twenty tests, two files: 10 pure unit tests over the most critical class
(`StockService`) and 10 full-stack integration tests over the end-to-end HTTP
contract. Both files mark all three AAA phases throughout.

`OpenApiSpecExporter` also lives under `src/test/` but is **not** one of the
20 tests — it's a `@Tag("docgen")` build utility that exports the OpenAPI spec
and is excluded from `./gradlew test` (see §6).

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
| **Spring Boot Test** | 4.1.0 | `@SpringBootTest`, `MockMvc` |

All arrive through `spring-boot-starter-webmvc-test`. You don't pick their
versions — the Spring Boot BOM does.

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
the assertion (illustrative shape):

```java
@Test
void httpErrorBecomesFinancialDataException() {
    // Arrange — a collaborator stubbed to fail

    assertThatThrownBy(() -> provider.fetchStock("MSFT"))            // Act + Assert
        .isInstanceOf(FinancialDataException.class)
        .hasMessageContaining("HTTP 500");
}
```

This is normal and correct. `assertThatThrownBy` (AssertJ) and
`assertThrows` (JUnit) both exist for it. In this project the failure paths
are exercised end-to-end instead — `StockScreenerIntegrationTest`'s
`completeProviderOutageWithColdCacheYields503` and `unknownTickerYields404ProblemDetail`
assert the HTTP status the error is translated into.

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

Both test classes mark all three phases with `// Arrange`, `// Act` and
`// Assert` comments (`// Act & Assert` where the operation and its
verification are necessarily one call, as with `assertThatThrownBy`):

- [`StockServiceTest`](../backend/src/test/java/com/stocklens/service/StockServiceTest.java) — the clearest set of examples; 10 tests, every phase separated onto its own line.
- [`StockScreenerIntegrationTest`](../backend/src/test/java/com/stocklens/StockScreenerIntegrationTest.java) — the full HTTP stack; Act captures the `MockMvc` `ResultActions` result *before* the Assert block chains `.andExpect(...)`/`assertThat(...)` on it, and `searchAndDetailEndpointWorkTogether` has two Act/Assert pairs, labelled, because that test deliberately checks a two-request sequence.

Where there's nothing to arrange — request-validation tests that never reach
the mocked service — the comment stays and says so explicitly
(`// Arrange — no stubbing needed; validation happens before the service is
called`) rather than being silently dropped, so the three-phase shape is
never ambiguous.

---

## 3. Unit testing

### What makes a test a unit test

It exercises **one class** with everything it depends on replaced by fakes.
No network, no database, no framework, no clock, no filesystem.

The practical test: a unit test is **fast** (milliseconds), **deterministic**
(same result every run, forever), and **isolated** (its result doesn't depend
on any other test).

`StockServiceTest` is the model: 10 tests, real `StockService`, fake provider,
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
| **Fake** | A real but simplified implementation | `MutableClock` (MockWebServer is the classic HTTP example, but it's no longer a dependency of this project) |
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

`ArgumentCaptor` answers "what exactly was passed?" — the classic use is to
prove a controller translated `?sortBy=pe` into `SortBy.PE` without depending
on the service at all. (This project verifies that translation end-to-end
through `StockScreenerIntegrationTest` instead, since it no longer keeps an
isolated web-layer slice.)

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

`StockServiceTest` has 10 tests split into five inner classes:

```java
@Nested class Search { ... }               // 1 test
@Nested class Filtering { ... }            // 2 tests
@Nested class Sorting { ... }              // 3 tests
@Nested class CachingAndResilience { ... } // 3 tests
@Nested class SingleStockLookup { ... }    // 1 test
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

That list is the kind of edge coverage `StockServiceTest` concentrates on for
a class with two public methods.

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

This project: **10 pure unit tests** (`StockServiceTest` — no Spring at all)
and **10 that boot the full context** (`StockScreenerIntegrationTest`, a
full-context `@SpringBootTest`). Deliberate. The `@SpringBootTest` class pays
for a Spring context, so those tests cover connections, not permutations. (The
`OpenApiSpecExporter` docgen utility also boots Spring, but it's not part of
the 20-test suite — see §6.)

### Slice tests — the middle tier (illustrative)

`@WebMvcTest` starts *only* the web layer. Real JSON serialization, real
validation, real exception handler — but no service, no provider, no cache.
**This project no longer ships a `@WebMvcTest` class** — the snippet below is
kept purely to illustrate the technique; the web layer's HTTP contract is now
covered through the full-stack `StockScreenerIntegrationTest` instead.

```java
// ILLUSTRATIVE — no such class exists in this repo anymore
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

A slice like this is the sweet spot for testing an HTTP contract in isolation:
it catches a wrong status code or a renamed JSON field without the cost of
booting everything. When a project keeps its suite small, those same contract
checks can instead ride along on the full-stack integration test — which is
the trade-off this project made.

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
   without it, a snapshot cached by one test leaks into a later one and the
   cold-cache `503` test fails. **This is the classic integration-test trap:
   shared state between tests.**
3. **Ten tests, and no more.** They cover the paths that unit tests
   structurally cannot: does the whole chain connect, does a cold-cache outage
   really produce a 503 over HTTP, and does the web layer's HTTP contract hold
   — the validation `400`s (`zeroMaxPeIsRejected`,
   `negativeMinMarketCapIsRejected`, `nonNumericFilterValueIsRejected`,
   `unknownSortFieldIsRejected`, `malformedTickerIsRejected`), the
   `unknownTickerYields404ProblemDetail`, and the
   `completeProviderOutageWithColdCacheYields503`. Those contract checks used
   to live in a separate `@WebMvcTest` slice; they now ride the full stack.

### The smoke test — folded into the integration test

The classic smoke test is a one-liner with an empty body:

```java
// ILLUSTRATIVE — the standalone context-load test was removed
@SpringBootTest
class StocklensApplicationTests {
    @Test void contextLoads() { }
}
```

It's one of the highest-value shapes there is: it fails if any bean can't be
created — a missing dependency, a bad `@Value`, a duplicate bean — catching
"the app won't start" before anything else runs. This project no longer keeps
a dedicated context-load test, but it gets the same guarantee for free:
`StockScreenerIntegrationTest` is a full-context `@SpringBootTest`, so every
one of its ten tests already fails if the context can't boot.

### What *not* to do in an integration test

- Don't re-test business rules. That `maxPe` is inclusive is a unit test's
  job. Duplicating it here just makes the suite slow.
- Don't hit real external services. Ever.
- Don't depend on test execution order.

---

## 5. Performance testing

Different question. Correctness tests ask *"is the answer right?"*.
Performance tests ask *"how fast, and how does it degrade under load?"*.

**This project ships no performance tests.** Earlier revisions had a pair of
`assertTimeout`-based regression guards; they were removed to keep the suite
focused at 10 unit + 10 integration. This section stays as a short conceptual
note on the technique, not a description of code in the repo.

### The technique, and its trap

A JUnit 5 `assertTimeout` regression guard is a valid, cheap way to catch an
accidental algorithmic blow-up (an `O(n)` lookup turning `O(n²)`):

```java
@Test
void searchIsFast() {
    assertTimeout(Duration.ofMillis(50), () -> service.search(query));
}
```

**But be careful.** It looks like a performance test and can easily become a
flakiness generator:

- The JVM is slow for the first few thousand runs (JIT hasn't compiled yet),
  so it measures warm-up, not steady state.
- A garbage collection pause, a busy CI machine, or a laptop on battery
  changes the number.
- A tight budget (50ms) is arbitrary. When it fails, nobody knows if the code
  regressed or the machine was busy, so the usual fix is to raise the limit
  until it stops failing — at which point it asserts nothing.

Used well, the point is *not* to assert a tight number but to catch a 10×
regression with a deliberately generous budget. That's a coarse, honest goal —
and the reason such a guard, if you keep one, belongs nowhere near a
micro-benchmark's precision.

### If you need to measure for real

`assertTimeout` is the cheapest rung on a ladder. The real tools live above it:

| Level | Question | Tool |
| --- | --- | --- |
| **Regression guard** | Did this get *dramatically* slower (10×, not 10%)? | **JUnit 5 `assertTimeout`** |
| **Micro-benchmark** | How long does this method take, precisely? | **JMH** |
| **Load test** | What happens at 500 requests/second? | **k6**, **Gatling**, **JMeter** |
| **Profiling** | Where is the time actually going? | **async-profiler**, JFR, IntelliJ profiler |

**JMH** (Java Microbenchmark Harness) is the only credible way to *measure*
Java code precisely — it handles warm-up, runs many iterations, and reports
variance — and it belongs in a separate source set, never in `./gradlew test`.
**Load testing** targets the running API over HTTP: a k6 script against
`/api/stocks` answers what p95 latency looks like at N concurrent users.

For this system, the interesting performance facts are architectural, not
algorithmic — a warm request is a field read plus a filter (microseconds),
while a cold request makes ~54 serial HTTP calls to Finnhub on the request
thread (see [ADR-001](adr/0001-no-database.md)). Measuring that gap would need
JMH or k6 against a running instance, not a JUnit `assertTimeout` on something
network-shaped.

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
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

`spring-boot-starter-webmvc-test` is one line that pulls in JUnit Jupiter,
AssertJ, Mockito, `MockMvc` and Spring's test context support — the whole
table in §1 arrives through it. `junit-platform-launcher` is what lets Gradle
actually *discover and run* JUnit 5 tests, not just compile them.

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
2. Discovers the behavioural test classes under `src/test/java` — the two in
   the table above, 20 tests total. The `@Tag("docgen")` `OpenApiSpecExporter`
   is deliberately **excluded** from this task (see below), so it does not run
   here.
3. Runs them (in parallel across classes, sequentially within a class, by
   default) and writes:
   - `build/test-results/test/*.xml` — machine-readable, one file per class
     (what generated the counts in the table above).
   - `build/reports/tests/test/index.html` — human-readable results.
4. Triggers `jacocoTestReport`.

The docgen exporter is kept out of the suite with a tag filter, e.g.:

```groovy
tasks.named('test') {
    useJUnitPlatform { excludeTags 'docgen' }
    finalizedBy tasks.named('jacocoTestReport')
}
```

`OpenApiSpecExporter` runs only via `./gradlew generateOpenApiSpec` (which
`./gradlew exportOpenApiSpec` depends on), which writes the spec to
`build/openapi/`. It boots a Spring context but asserts nothing about
behaviour — it's a build utility, not one of the 20 tests.

### Useful task invocations

```bash
./gradlew test                                  # everything (20 tests)
./gradlew test --tests '*StockServiceTest*'      # one class
./gradlew test --tests '*.CachingAndResilience'  # one @Nested group
./gradlew test --rerun                           # ignore Gradle's up-to-date cache
./gradlew bootRun                                # run the app for real
./gradlew exportOpenApiSpec                      # regenerate docs/openapi.yaml (runs the docgen exporter)
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
| **Instruction** | Bytecode instructions executed | **63.0%** |
| **Branch** | `if`/`switch`/`?:` outcomes taken | **49.0%** |
| **Line** | Source lines touched | 97.0% |
| **Method** | Methods called at least once | 100% |

**Branch coverage is the number to watch.** Line coverage counts a line as
covered if it ran at all; branch coverage asks whether *both* outcomes
happened. `if (a || b)` on one line can be "100% line covered" with three of
four branches untested. The branch number is the honest one — and here it
tells a blunt story.

### What the report found in this project

Coverage is a **map of where you haven't looked**, and the biggest blank
region is obvious: the entire `FinnhubStockProvider` client — the class that
talks to Finnhub over HTTP and maps the raw JSON into `Stock` objects — has
**no automated test at all**. Its raw HTTP mapping is only ever exercised by
hand against the live API. That single gap is what dragged instruction
coverage from ~97% down to 63% and branch coverage to 49%. `StockController`
and its error/validation mapping stay covered, because the full-stack
integration tests drive them end-to-end.

Reading the yellow and red also surfaces a subtler gap that survives even
where a class *is* tested:

| Location | Untested | Real risk? |
| --- | --- | --- |
| `StockService:151` | The double-check inside `synchronized refresh()` — "another thread refreshed while we waited" | **Yes.** All 20 tests are single-threaded, so no test has ever exercised the concurrency guard. |

That one is invisible by reading the tests. That is what coverage is for.

### Excluded from the numbers

```groovy
exclude: [
    'com/stocklens/StocklensApplication.class',
    'com/stocklens/config/**',
]
```

`main()` and the `@Configuration` classes are framework wiring with no branches
to get wrong. Including them would inflate the percentage with code whose only
meaningful test is "the app starts" — which every `@SpringBootTest` in
`StockScreenerIntegrationTest` already exercises by booting the context.
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
holding real business rules. This project keeps high branch coverage on
`StockService` — the screening logic — but `FinnhubStockProvider` sits at
**0%**, because no test touches the API client at all. That split is honest:
the business rules are well covered, and the untested API client is the
project's single biggest coverage gap, an acknowledged trade-off of the
20-test suite rather than an oversight.

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
