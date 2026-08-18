package com.stocklens.domain;

import java.time.Instant;

/**
 * An immutable snapshot of one company's screening data, as served by
 * {@code GET /api/stocks}.
 *
 * <p>Every metric is a boxed {@code Double} rather than a primitive because
 * the external provider does not guarantee every metric for every company:
 * P/E is undefined for a company without positive earnings, and a company
 * that pays no dividend has no yield. Callers must treat these as nullable.
 * Only {@code ticker}, {@code name} and {@code lastUpdated} are always
 * present; {@code price} is always present for stocks that reach this record,
 * because the provider discards quotes it cannot price.
 *
 * <p>Example:
 * <pre>{@code
 * Stock msft = new Stock("MSFT", "Microsoft Corp", "Technology",
 *         512.30, 29.5, 3.1e12, 0.71, Instant.now());
 *
 * msft.marketCap();   // 3.1e12 — plain USD, not millions
 * msft.peRatio();     // 29.5, or null if the company reports no P/E
 * }</pre>
 *
 * @param ticker        exchange symbol in upper case, e.g. {@code "MSFT"};
 *                      never {@code null}
 * @param name          company name as reported by the provider, e.g.
 *                      {@code "Microsoft Corp"}; never {@code null}
 * @param sector        industry classification, e.g. {@code "Technology"};
 *                      {@code null} if the provider does not classify it
 * @param price         last trade price in USD; {@code null} only if the
 *                      snapshot was built without a quote
 * @param peRatio       trailing price/earnings ratio; {@code null} when
 *                      undefined, and negative for a loss-making company
 * @param marketCap     market capitalization in <strong>plain USD</strong>
 *                      (3.1e12, not 3_100_000 millions); {@code null} if
 *                      unreported
 * @param dividendYield trailing dividend yield as a percentage, e.g.
 *                      {@code 0.71} for 0.71%; {@code null} if none
 * @param lastUpdated   instant the data was fetched from the provider, used
 *                      to reason about staleness; never {@code null}
 */
public record Stock(
		String ticker,
		String name,
		String sector,
		Double price,
		Double peRatio,
		Double marketCap,
		Double dividendYield,
		Instant lastUpdated) {
}
