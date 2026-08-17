package com.stocklens.domain;

import java.time.Instant;

/**
 * A snapshot of one company's screening data. Metric fields are nullable
 * because the external provider does not guarantee every metric for every
 * company (e.g. P/E is undefined for companies without positive earnings).
 * {@code marketCap} is in plain US dollars.
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
