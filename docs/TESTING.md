# Testing

How this project is tested, and the concepts behind it. Every example is real
code from the one test class in the suite:
[`StockServiceTest`](../backend/src/test/java/com/stocklens/service/StockServiceTest.java).

The suite is deliberately small: **10 unit tests over `StockService`**, written
for a unit-testing practice. Each test follows the **Arrange-Act-Assert**
pattern with the phases explicitly marked, and the set is split into happy
paths (1–4) and boundaries/unhappy paths (5–10). Earlier revisions of this
repo carried a 57-test pyramid (unit + slice + integration + performance);
see [§5](#5-what-this-suite-deliberately-leaves-out) for what was cut and why
it would matter in a real project — `git log` has the full version.

**Quick commands**

```bash
cd backend
./gradlew test                                   # 10 tests + coverage report
open build/reports/tests/test/index.html         # test results
open build/reports/jacoco/test/html/index.html   # coverage
```

---

## 1. What a test is

A method that runs your code with known inputs and fails the build if the
result is wrong.

```java
@Test
void maxPeBoundaryIsInclusive() {
    // Arrange — MSFT's P/E is exactly 29.5, AAPL's is 30.1
    serviceWith(MSFT, AAPL);

    // Act
    List<Stock> results = service.search(query(null, 29.5, null));

    // Assert — a P/E exactly on the limit passes the filter
    assertThat(tickers(results)).containsExactly("MSFT");
}
```

That's the whole idea. `@Test` marks it, JUnit runs it, an assertion decides
pass or fail. If nothing throws, it passes.

**Why bother.** Not to prove the code works today — you could check that by
hand. It's to prove it *still* works after tomorrow's change. A test suite is
a machine that answers "did I just break something?" in five seconds instead
of twenty minutes of clicking.

### The libraries here

| Library | Job |
| --- | --- |
| **JUnit Jupiter** | Finds and runs tests (`@Test`, `@ExtendWith`) |
| **AssertJ** | Readable assertions (`assertThat(x).isEqualTo(y)`, `assertThatThrownBy`) |
| **Mockito** | Fakes the provider (`@Mock`, `when(...).thenReturn(...)`) |

No Spring is involved anywhere in the suite: the tests construct
`StockService` with `new`, which is exactly what makes them unit tests.

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

Test 10 is the richest Arrange in the suite — it has to build a *history*
(cache primed, time passed, provider now failing) before the Act:

```java
@Test
void failedRefreshServesStaleDataInsteadOfFailing() {
    // Arrange — a successful fetch primes the cache, then the TTL expires
    // and the provider starts rejecting calls
    serviceWith(MSFT, GOOGL);
    service.search(query(null, null, null));
    clock.advance(TTL.plusSeconds(1));
    when(provider.fetchStock(anyString())).thenThrow(new RateLimitedException("HTTP 429"));

    // Act — this search triggers a refresh that fails
    List<Stock> results = service.search(query(null, null, null));

    // Assert — graceful degradation: stale data, not an exception
    assertThat(tickers(results)).containsExactlyInAnyOrder("MSFT", "GOOGL");
}
```

Note the first `service.search(...)` is **Arrange**, not Act — it exists to
put the cache in a known state. The Act is the *second* call, the one whose
behavior the test name describes.

### Why the order matters

**One Act per test.** If a test calls three different methods, a failure
doesn't tell you which one broke. The name should describe one behaviour.
(Test 7 runs the same sort in both directions — two calls, but one behaviour:
"missing metric goes last *in both directions*". The invariant is the Act.)

**Assert after Act, never during.** Assertions inside the arrange phase are
testing your setup, not your code.

**No logic in the test.** No `if`, no loops over cases, no computing the
expected value with the same formula the code uses. Write the expected value
as a literal: the test asserts `containsExactly("GOOGL", "MSFT", "NODATA")`
because a human decided that order, not because the test re-sorted the list.

### AAA when the Act throws

Testing an exception merges Act and Assert, because the call has to be inside
the assertion:

```java
@Test
void unknownTickerThrowsStockNotFound() {
    // Arrange — ZZZZ is not part of the screened universe
    serviceWith(MSFT, GOOGL);

    // Act + Assert — the call under test lives inside the assertion
    assertThatThrownBy(() -> service.getByTicker("ZZZZ"))
        .isInstanceOf(StockNotFoundException.class)
        .hasMessageContaining("ZZZZ");
}
```

This is normal and correct. `assertThatThrownBy` (AssertJ) and
`assertThrows` (JUnit) both exist for it.

### Where the Arrange phase goes when it repeats

Most tests share setup. Rather than repeating it, it moves into a helper:

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
named.

---

## 3. Unit testing

### What makes these tests unit tests

Each one exercises **one class** — `StockService` — with everything it
depends on replaced by fakes. No network, no database, no framework, no real
clock, no filesystem.

```java
@ExtendWith(MockitoExtension.class)   // enables @Mock, no Spring involved
class StockServiceTest {

    @Mock
    private FinancialDataProvider provider;      // fake

    private final MutableClock clock = new MutableClock();   // fake

    private StockService service;                // real — the thing under test
}
```

The practical test: a unit test is **fast** (milliseconds), **deterministic**
(same result every run, forever), and **isolated** (its result doesn't depend
on any other test).

### The 10 tests

| # | Path | Test | The rule it pins down |
| --- | --- | --- | --- |
| 1 | Happy | `searchWithoutFiltersReturnsWholeUniverse` | No filters → everything comes back |
| 2 | Happy | `textSearchMatchesCompanyNameIgnoringCase` | `"micRO"` finds Microsoft |
| 3 | Happy | `maxPeBoundaryIsInclusive` | A P/E exactly on the limit **passes** |
| 4 | Happy | `getByTickerIgnoresCaseAndWhitespace` | `" msft "` finds MSFT |
| 5 | Unhappy | `companyWithNullPeIsExcludedByPeFilter` | "Unknown P/E" is not "cheap" |
| 6 | Boundary | `negativePeStillPassesMaxPeFilter` | A reported loss is below any positive ceiling |
| 7 | Boundary | `companyMissingTheSortedMetricGoesLastInBothDirections` | Reversing the sort must not promote missing data |
| 8 | Unhappy | `unknownTickerThrowsStockNotFound` | Absent ticker → domain exception, not `null` |
| 9 | Unhappy | `rateLimitWithColdCacheThrowsDataUnavailable` | Cold cache + 429 → `DataUnavailableException` |
| 10 | Unhappy | `failedRefreshServesStaleDataInsteadOfFailing` | Warm cache + 429 → stale data, **no** exception |

Tests 9 and 10 are a deliberate pair: the *same* provider failure produces
opposite outcomes depending on cache state. The behavior under test is not
the error — it's the service's **policy** toward the error.

### Why boundaries outnumber happy paths

The happy path is the easy case and rarely where bugs live. Six of the ten
tests target edges: exactly on the limit (3), missing data (5, 7), a negative
value (6), an absent key (8), and failure of the outside world (9, 10). Each
edge is one `if` in `StockService` that could be written wrong — inclusive vs
exclusive is the difference between `>` and `>=`.

### Test doubles

"Mock" is used loosely to mean any fake. The precise vocabulary, mapped to
this suite:

| Kind | What it does | Example here |
| --- | --- | --- |
| **Dummy** | Passed to satisfy a signature, never used | the `null` Finnhub config in `new StockLensProperties(null, screener)` |
| **Stub** | Returns canned answers | `when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT))` |
| **Mock** | A stub you also assert *was called* | *not needed in these 10* — every assert is on a returned value |
| **Fake** | A real but simplified implementation | `MutableClock` |

The distinction that matters: a **stub** helps you arrange; a **mock** (in
the strict sense, with `verify(...)`) is part of your assertion. Verifying
calls couples the test to *how* the code works, so this suite asserts on
values instead — the returned lists and thrown exceptions are the observable
behavior.

### Determinism: the injected clock

The rule "a test must give the same answer every time" is why `Clock` is a
constructor parameter of `StockService` and not `Instant.now()` inside it:

```java
private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-08-17T12:00:00Z");
    void advance(Duration duration) { instant = instant.plus(duration); }
    @Override public Instant instant() { return instant; }
}
```

Test 10 "waits" ten minutes by calling `clock.advance(TTL.plusSeconds(1))` —
in microseconds. The alternative — `Thread.sleep` with a shortened TTL — is
slow *and* flaky, because it fails whenever the machine is briefly busy.
**Any test containing `sleep` is a bug waiting to happen.**
See [`adr/0004-inject-clock.md`](adr/0004-inject-clock.md) for the decision
in full.

### Naming

The name is the specification. It should say what holds, so a failure report
is readable without opening the file:

```
maxPeBoundaryIsInclusive
companyMissingTheSortedMetricGoesLastInBothDirections
failedRefreshServesStaleDataInsteadOfFailing
```

Not `testSearch1`, `testSearch2`. If you can't name it in one clause, the test
is probably doing two things.

---

## 4. Coverage

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

The HTML report is colour-coded per line: green = executed, red = never
executed, and **yellow = partially covered** — an `if` where only one outcome
happened. Yellow is the interesting colour.

### What the numbers say now

With only the `StockService` suite, coverage is an honest map of what this
practice does and does not test:

| Class | Instruction | Branch | Reading |
| --- | --- | --- | --- |
| `StockService` | 83.4% | 66.0% | The target of the suite. The uncovered branches are the concurrency double-check and error paths no single-threaded test reaches. |
| `Stock`, `StockQuery`, exceptions | ~100% | — | Dragged along by the service tests. |
| `StockQuery.SortBy` / `Direction` | 64% / 38% | **0%** | The `from(String)` parsers are **never called** — they were covered by the deleted controller tests. |
| `FinnhubStockProvider` | **0%** | **0%** | Untested since its suite was deleted. |
| `StockController`, `GlobalExceptionHandler` | **0%** | — | Same. |
| **Total** | **47.9%** | **34.4%** | |

Two lessons worth taking from that table:

1. **Coverage is a map of where you haven't looked.** The 0% rows are not a
   scandal — they are the *known, deliberate* consequence of scoping this
   practice to one class. What would be a problem is not knowing they exist.
2. **Deleting tests un-covers code you didn't delete tests for.**
   `SortBy.from()` lives in the service package, but its coverage came
   entirely from the web-layer tests. Coverage tells you who was really
   testing what.

**Branch coverage is the number to watch.** Line coverage counts a line as
covered if it ran at all; branch coverage asks whether *both* outcomes of
every `if` happened. That's why `StockService` shows 83% instruction but 66%
branch — the branch figure is the honest one.

### Why 100% is the wrong goal

Coverage is a **necessary but not sufficient** condition. Code that never
runs is definitely untested; code that runs is only *maybe* tested. A test
that calls `search()` and asserts nothing covers every line of it and can
never fail. **Use coverage to find the gaps you forgot, then judge each
one** — a deliberate decision not to test the concurrency double-check is
fine; not knowing it was untested is not.

---

## 5. What this suite deliberately leaves out

The repo previously carried the full pyramid; this practice keeps only the
unit tier. What was cut, and the gap each cut opens:

```
        ╱  few  ╲        integration — slow, broad, catches wiring      (removed)
      ╱─────────╲
    ╱   slice    ╲       @WebMvcTest — the HTTP contract                (removed)
  ╱───────────────╲
 ╱   unit (10)     ╲     fast, narrow, catches logic                    (kept)
```

- **Controller slice tests** (`@WebMvcTest` + `MockMvc`): proved that
  `?sortBy=pe` became `SortBy.PE`, that bad input returned 400, that
  exceptions mapped to 404/503 problem details. Without them, the HTTP
  contract — status codes, JSON field names, validation — is unverified.
- **Provider tests** (MockWebServer): proved the Finnhub client survives
  HTTP 500, timeouts, missing JSON fields and rate limits. That error
  handling is now the biggest untested surface in the codebase.
- **Integration tests** (`@SpringBootTest`): proved the pieces are *wired*
  — that the real chain controller→service→provider connects, and that a
  cold-cache outage really surfaces as a 503 over HTTP. Unit tests
  structurally cannot catch a missing bean or a broken binding.
- **The smoke test** (`contextLoads`): one empty test that failed if the
  Spring context couldn't start. Highest value per line of code in the old
  suite.

For a unit-testing practice this scope is correct. For a shipping project,
the pyramid exists because each tier catches a class of bug the others
cannot — all of it is one `git log` away.

---

## 6. Rules of thumb

- **One behaviour per test**, named as a sentence about that behaviour.
- **Assert on values, not on implementation** — verify interactions only
  when the interaction *is* the requirement.
- **No `sleep`, no real clock, no network, no random, no dependence on test
  order.** Any of those is a future flaky failure.
- **Test the boundaries**, not the happy path.
- **A failing test should tell you what broke from its name alone.**
- **Write the test first when fixing a bug** — it should fail, then pass.
  That proves the test actually detects the bug.
- **Coverage finds gaps; it does not measure quality.**

---

## See also

- [`ARCHITECTURE.md`](ARCHITECTURE.md#testing-architecture) — the test tiers in context
- [`FUNDAMENTALS.md`](FUNDAMENTALS.md#16-why-this-shape-makes-testing-possible) — why the layering is what makes unit testing possible
- [`adr/0004-inject-clock.md`](adr/0004-inject-clock.md) — the injected-clock decision, in full
