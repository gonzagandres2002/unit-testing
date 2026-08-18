# ADR-006: Finnhub as the financial data provider

## Status

Accepted

## Context

The screener needs U.S. company names, current price, P/E, market cap and
sector, on a free tier with enough quota to survive a day of development and
manual testing. Six APIs were compared against those requirements on
2026-08-17; the full evidence, with per-API detail and source URLs, is in
[`../API-RESEARCH.md`](../API-RESEARCH.md).

The deciding constraints were **quota** and **whether fundamentals are free**:

| API | Free limit | P/E | Market cap | Verdict |
| --- | --- | :-: | :-: | --- |
| **Finnhub** | 60 req/min, no documented daily cap | ✓ | ✓ | Selected |
| Financial Modeling Prep | 250 req/day | ✓ | ✓ | Quota too tight |
| Alpha Vantage | 25 req/day | ✓ | ✓ | Quota unusable |
| Twelve Data | 800/day | ✗ paid | ✗ paid | Fundamentals paywalled |
| Marketstack | 100 req/month | ✗ never | ✗ never | Not viable |
| Polygon.io | 5 req/min | ✗ $199/mo | ✓ | Fundamentals paywalled |

## Decision

Use Finnhub, combining three free endpoints per ticker: `/stock/profile2`
(name, industry, market cap), `/quote` (price) and `/stock/metric` (P/E,
dividend yield).

## Rationale

- **Only Finnhub offers every required field on a genuinely usable free
  tier.** Three of the six paywall fundamentals; two more have quotas that a
  single afternoon of development would exhaust.
- **A per-minute limit suits a cached screener.** 60/minute with no daily cap
  means the constraint is burst rate, which a TTL cache smooths out by design
  — unlike a daily cap, which development alone would consume.
- **Real-time prices**, where most free tiers offer end-of-day only.
- **Registration is frictionless**: no credit card, no approval.
- **Failures are well-behaved.** Exceeding the limit returns HTTP 429, so it
  can be detected and handled as a distinct condition. Alpha Vantage, by
  contrast, signals rate limiting with `200 OK` and a sentinel JSON body.

## Consequences

**Accepted costs**

- **Quota governs the universe size.** One refresh costs 3 calls × 18 tickers
  = 54, against a 60/minute limit. Growing the universe past ~20 tickers
  requires batching, a longer TTL, or a paid tier. This is the single hardest
  constraint on the product and is documented at the `tickers` property.
- **Three calls per ticker** where a single-call API would need one — the
  price of using free endpoints only.
- **Undocumented metric field names.** Finnhub does not enumerate the keys in
  `/stock/metric`, so the client tries several known candidates in order
  (`peTTM`, `peBasicExclExtraTTM`, `peNormalizedAnnual`) and treats every
  metric as optional. Field names may change without notice.
- **Protocol quirks** that had to be handled explicitly: an unknown symbol
  returns `200 OK` with `{}` rather than a `404`, a delisted symbol returns an
  all-zero quote, and market cap is reported in millions.
- **The free licence is "personal use"**, which suits a course project but
  would need review before any public deployment.
- **Historical candles are premium-only** — irrelevant to the MVP, but the
  roadmap's historical-charts feature cannot be built on this tier.

**What it buys**

- Every MVP field available at zero cost, with no second data source needed.
- Automated tests consume no quota at all, because they mock the provider or
  run against a local `MockWebServer`.

**When to revisit**

If the universe needs to grow substantially, or historical data becomes a
requirement, a second `FinancialDataProvider` implementation is the migration
path — see [ADR-002](0002-provider-abstraction.md). Nothing above the provider
interface would change.
