# ADR-004: Inject `Clock` instead of calling `Instant.now()`

## Status

Accepted

## Context

Two behaviours depend on the current time: the 10-minute cache TTL, and the
`lastUpdated` stamp on every `Stock`.

Written the usual way, both would call `Instant.now()` inline. That makes the
TTL logic effectively untestable. To verify "an expired cache is refreshed",
a test would have to either sleep for ten minutes, or shrink the TTL to
milliseconds and sleep for those — trading a slow test for a flaky one, since
a timing-based test fails whenever CI is briefly loaded.

Options considered:

1. `Instant.now()` inline; test TTL with a tiny TTL and `Thread.sleep`
2. Inject `java.time.Clock` as a bean
3. Wrap time in a project-specific `TimeProvider` interface

## Decision

Expose `Clock.systemUTC()` as a Spring bean in `AppConfig` and inject it into
`StockService` and `FinnhubStockProvider`. All time is read through it.

## Rationale

`Clock` already exists in the JDK for exactly this purpose, so option 3 adds a
type without adding capability. `Clock.fixed(...)` covers the provider's need
for a deterministic timestamp, and a small subclass covers the service's need
for time that moves on command:

```java
private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-08-17T12:00:00Z");
    void advance(Duration duration) { instant = instant.plus(duration); }
    @Override public Instant instant() { return instant; }
    // ...
}
```

A ten-minute TTL is then tested in microseconds, deterministically:

```java
service.search(query(null, null, null));
clock.advance(TTL.plusSeconds(1));
service.search(query(null, null, null));
verify(provider, times(4)).fetchStock(anyString());
```

There is no sleep, no tolerance window, and no possibility of a load-dependent
failure. The test asserts the *rule* — expiry triggers a refetch — rather than
observing a side effect of real elapsed time.

The same clock gives `FinnhubStockProviderTest` a fixed `lastUpdated`, so the
mapping test can assert an exact value instead of a range.

## Consequences

**Accepted costs**

- One more constructor parameter on two classes, and a bean that looks
  redundant to a reader who has not seen the tests.
- The discipline is unenforced: nothing stops someone from calling
  `Instant.now()` in a new class and silently reintroducing an untestable
  dependency on real time.

**What it buys**

- Cache expiry, stale-serving and refresh-on-expiry are all deterministically
  testable — three of the eight tests in `CachingAndResilience` depend on it.
- The whole 52-test suite runs in about five seconds, with no sleeps anywhere.
- Time-dependent behaviour can be tested at any point on the timeline, not
  just "now".
