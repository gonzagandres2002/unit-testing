# Phase 1 — External Financial API Research

Research date: **2026-08-17**. All facts were taken from official documentation and
pricing pages (URLs listed at the bottom). Six candidate APIs were compared.

## Requirements recap

The MVP needs, on a **free tier practical for development and automated testing**:

- U.S. stock support, company names and tickers
- Current price
- P/E ratio
- Market capitalization
- Sector/industry (nice to have: dividend yield, EPS)
- Enough request quota that development + manual testing does not exhaust it
  (automated tests mock the API and consume no quota)

## Comparison

| API | Free limit | U.S. stocks | Price | P/E | Market Cap | Sector | Easy to use |
| --- | ---: | :-: | :-: | :-: | :-: | :-: | :-: |
| **Finnhub** | **60 req/min, no daily cap documented** | ✓ | ✓ real-time | ✓ | ✓ | ✓ (industry) | ✓ (flat JSON, key in header, HTTP 429 on limit) |
| Financial Modeling Prep | 250 req/day | ✓ | ✓ EOD only | ✓¹ | ✓ | ✓ | ✓ (HTTP 429 on limit) |
| Alpha Vantage | **25 req/day** | ✓ | ✓ EOD only | ✓ | ✓ | ✓ | ✗ (numbers as strings, rate limit = HTTP 200 + sentinel body) |
| Twelve Data | 8 credits/min, 800/day | ✓ | ✓ real-time | ✗ paid | ✗ paid | ✗ paid | ✓ |
| Marketstack | **100 req/month** | ✓ | ✓ EOD only | ✗ not offered at any tier | ✗ not offered | ✓ | ✓ |
| Polygon.io (now "Massive") | 5 req/min | ✓ | ✓ EOD only | ✗ paid ($199/mo tier) | ✓ | ~ (SIC code only) | ✓ |

¹ FMP's free/paid endpoint matrix could not be fully machine-verified (icon-rendered
pricing table); its FAQ confirms annual statements and profile data are free.

Details per API:

- **Finnhub** — Free plan: 60 API calls/minute (plus a global 30 calls/second cap);
  no daily/monthly quota appears anywhere in official docs. Free API key by plain
  registration, no credit card, no approval. Free endpoints cover everything the MVP
  needs: `/quote` (real-time U.S. price), `/stock/profile2` (name, market cap,
  industry), `/stock/metric` (P/E, EPS, key metrics), `/stock/symbol` (full U.S.
  ticker list). Exceeding the limit returns HTTP 429. Caveats: historical candles
  are premium-only (irrelevant to the MVP); exact metric field names (e.g. `peTTM`)
  are not enumerated in the docs, so the client reads them defensively; the free
  license is "personal use".
- **Financial Modeling Prep** — Solid data (quotes, profiles, ratios incl. P/E and
  dividend yield) but the free tier is a hard 250 requests/day with end-of-day
  prices only and a 500 MB/30-day bandwidth cap. Workable, but an afternoon of
  development plus manual testing can realistically burn 250 calls.
- **Alpha Vantage** — The `OVERVIEW` endpoint conveniently returns P/E, market cap,
  dividend yield and EPS in one call, but the free tier is 25 requests/day — exactly
  the kind of quota the project constraints rule out. Rate-limit responses arrive as
  HTTP 200 with an `"Information"` JSON body, which makes robust error handling ugly.
- **Twelve Data** — Free tier is price data only (800 credits/day, 8/min).
  All fundamentals (profile, statistics, P/E, market cap) are paywalled from the
  $29/mo tier upward. Demo keys answer for a few "trial symbols", which can mask the
  paywall during development.
- **Marketstack** — 100 requests/month, end-of-day only, and no P/E or market cap
  at *any* price tier. Not viable.
- **Polygon.io / Massive** — Free tier has no daily cap but only 5 calls/minute and
  end-of-day prices. Market cap is free (`/v3/reference/tickers/{t}`), but P/E and
  dividend yield require the $199/mo plan or a paid add-on. Not viable without a
  second data source.

## Decision: **Finnhub**

Reasons, in order of importance:

1. **Only API where all required fields are free.** Finnhub, FMP and Alpha Vantage
   are the only candidates with a free P/E ratio; the other three would need a paid
   plan or a second API just for fundamentals.
2. **Quota is orders of magnitude more practical.** 60 requests/minute with no
   documented daily cap vs. 250/day (FMP) and 25/day (Alpha Vantage). A full refresh
   of the MVP's 18-company universe costs 54 calls (3 endpoints × 18 tickers) — under
   one minute of quota on Finnhub, but more than a fifth of FMP's *daily* budget and
   twice Alpha Vantage's.
3. **Clean integration and failure semantics.** Flat JSON, API key via the
   `X-Finnhub-Token` header, real HTTP status codes (429 on rate limit) — all easy to
   handle and to simulate in tests. Alpha Vantage's 200-with-sentinel-body errors
   were a significant negative.
4. **Real-time U.S. quotes** on the free tier (the others are end-of-day).

Trade-offs accepted: market data is "personal use" licensed (fine for a course
project); the `/stock/metric` field names are not formally documented, so the client
treats P/E and dividend yield as optional and tries known key names defensively —
which the spec requires anyway ("missing financial data" must be handled).

## Sources

- https://finnhub.io/docs/api (endpoints, auth, rate limits, 429 behavior)
- https://finnhub.io/pricing (free plan: 60 calls/min, coverage, license)
- https://site.financialmodelingprep.com/developer/docs/pricing and /faqs (250/day, 429, free-tier scope)
- https://www.alphavantage.co/premium/ and /documentation/ (25/day, endpoints; behavior verified with the public demo key)
- https://twelvedata.com/pricing and https://support.twelvedata.com/en/articles/5615854-credits (credits, fundamentals tiers)
- https://marketstack.com/product and /faq (100/month, feature grid)
- https://massive.com/pricing and https://massive.com/docs/rest/stocks/fundamentals/ratios.md (5/min free tier; ratios paid-only)
