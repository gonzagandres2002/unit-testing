# ADR-003: Degrade gracefully; typed exceptions drive the policy

## Status

Accepted

## Context

Every piece of data the screener shows comes from a third-party free tier that
can fail in several distinct ways: one symbol returns garbage, the network
times out, the quota runs out, the whole service goes down.

The naive behaviour — propagate any provider failure to the client — means a
single bad ticker out of eighteen breaks the entire page. That is both a poor
experience and a misrepresentation: seventeen companies' data was retrieved
successfully.

The quota constraint sharpens it. Finnhub's free tier allows 60 calls/minute
and one refresh costs 54. Once a rate limit is hit, continuing to call for the
remaining tickers cannot succeed — it only deepens the hole.

## Decision

Fail only when there is genuinely nothing to serve. Four cases, each with a
distinct behaviour, selected by the *type* of the exception:

| Situation | Behaviour |
| --- | --- |
| One ticker fails (`FinancialDataException`) | Log, skip it, continue the refresh |
| Rate limit (`RateLimitedException`) | Abort the refresh immediately |
| Refresh failed, previous snapshot exists | Serve the **stale** snapshot with `200` |
| Refresh failed, cache cold | `DataUnavailableException` → `503` |

`RateLimitedException extends FinancialDataException`, so a caller that does
not care about the distinction still catches both.

## Rationale

**The type is the signal.** The alternative — one exception type carrying an
error code or a parseable message — pushes the decision to the call site as
string comparison. Instead, `refresh()` expresses the policy structurally:

```java
try {                                     // outer
    for (String ticker : tickers) {
        try {                             // inner, per ticker
            provider.fetchStock(ticker).ifPresent(fresh::add);
        }
        catch (RateLimitedException e) {
            throw e;                      // escape the loop entirely
        }
        catch (FinancialDataException e) {
            log.warn("Skipping {}: {}", ticker, e.getMessage());
        }                                 // continue to the next ticker
    }
}
catch (RateLimitedException e) {
    return staleOrUnavailable(existing, "the provider rate limit is exhausted");
}
```

The nesting is the policy. Catch order matters and is compiler-enforced:
because `RateLimitedException` is a subclass, placing the general catch first
would make the specific one unreachable and fail to compile.

**Stale data beats no data** for this product. A screener showing prices from
eleven minutes ago is useful; an error page is not. The `lastUpdated` field on
every `Stock` is what makes this honest — clients can see and surface the age
rather than being misled.

**`503`, not `500`.** The remaining failure case is an upstream, transient
condition. `503 Service Unavailable` tells a client to retry;`500` would imply
a bug in this service.

## Consequences

**Accepted costs**

- Clients can receive arbitrarily old data during a prolonged outage. There is
  no maximum staleness — a snapshot from hours ago is still served. Mitigated
  only by `lastUpdated` being visible; a hard staleness ceiling would be a
  reasonable future addition.
- A partial result is indistinguishable from a complete one. If three
  companies were skipped, the response is a normal `200` array with fifteen
  entries and no indication that three are missing.
- Failures are visible only in the logs.

**What it buys**

- A single misbehaving symbol cannot take down the endpoint.
- Rate-limit damage is bounded: the refresh stops at the first `429` instead
  of making 50 more doomed calls.
- The behaviour is directly testable. `StockServiceTest.CachingAndResilience`
  covers all four branches, including asserting that exactly **one** provider
  call is made before a rate-limited refresh aborts.

**When to revisit**

If partial results become misleading in practice, add a freshness or
completeness field to the response — that is an additive API change, not a
restructuring.
