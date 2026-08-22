# ADR-002: Isolate the external API behind a one-method interface

## Status

Accepted

## Context

The backend depends on Finnhub for all its data. That dependency is the
riskiest thing in the system: it is a third party, on a free tier, with
undocumented metric field names, a rate limit, and a "personal use" licence
(see [ADR-006](0006-finnhub-provider.md)). It may need to be swapped.

It is also the reason the code is hard to test. Anything that calls Finnhub
directly can only be tested against Finnhub — over the network, consuming
quota, with results that change every day.

Options considered:

1. Call the Finnhub client directly from `StockService`
2. Introduce a provider interface with one implementation
3. Build a full anti-corruption layer with its own model and mapper hierarchy

## Decision

A single interface, `FinancialDataProvider`, with one method:

```java
Optional<Stock> fetchStock(String ticker);
```

`FinnhubStockProvider` implements it. `StockService` depends only on the
interface.

## Rationale

**Testability was the immediate driver.** With the interface in place, the 27
tests in `StockServiceTest` drive a Mockito stub. They run offline, in
milliseconds, and can simulate a rate limit, a partial outage or a company
with a missing P/E — states that would be impossible to arrange against the
real API on demand.

**Replaceability was the second.** Finnhub's free tier is a product decision by
someone else. A second provider is a new class, not a rewrite, because nothing
above the interface imports a Finnhub type.

**Translation happens at the boundary.** The implementation catches Spring's
`RestClientResponseException` and `ResourceAccessException` and rethrows
`FinancialDataException` / `RateLimitedException` with the original as the
cause. No layer above the provider knows which HTTP client is in use.

Option 3 was rejected as premature. With one provider and one method, a
separate provider-side model and mapper would be ceremony; `Stock` is already
the translated form. If a second provider arrives with a genuinely different
shape, that is the moment to add one.

**The contract encodes three outcomes, not two**, because the service reacts
differently to each:

| Outcome | Meaning | Service reaction |
| --- | --- | --- |
| `Optional` with value | data retrieved | added to the snapshot |
| `Optional.empty()` | provider is fine, doesn't know this ticker | silently omitted |
| `RateLimitedException` | quota exhausted | abort the whole refresh |
| `FinancialDataException` | anything else | skip this ticker, continue |

An unknown ticker is deliberately not an exception: it is an expected outcome
of screening a configured list, not an error.

## Consequences

**Accepted costs**

- One more type than strictly necessary while there is exactly one provider.
- The interface's contract has to be honoured by every implementation, and it
  is enforced only by documentation and tests — not by the compiler.

**What it buys**

- `StockService` is unit-testable with no network.
- Provider quirks stay contained. Finnhub answers unknown symbols with
  `200 OK` and `{}`, reports market cap in millions, and does not document its
  metric field names. All of that is handled inside `FinnhubStockProvider` and
  invisible above it.
- The client can be tested in isolation against a local `MockWebServer`,
  covering HTTP 500, HTTP 429, timeouts and malformed JSON. (The current suite
  is trimmed to `StockService` unit tests and full-stack integration tests that
  mock this interface, so no such client test ships today — but the seam keeps
  one cheap to add.)
