package com.stocklens.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.stocklens.config.StockLensProperties;
import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataProvider;
import com.stocklens.service.StockQuery.Direction;
import com.stocklens.service.StockQuery.SortBy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Performance unit test for {@link StockService}: asserts the
 * search/filter/sort pipeline stays within a fixed time budget against a
 * universe far larger than production's 18 tickers, so a future change that
 * makes it accidentally quadratic (or worse) fails the build instead of only
 * showing up as a slow endpoint in production.
 *
 * <p>This is a lightweight JUnit 5 timing guard ({@link #assertTimeout}), not
 * a micro-benchmark — no warm-up iterations, no statistical distribution. It
 * stays a <em>unit</em> test because the provider is an in-memory fake, not
 * Spring or the network: only {@code StockService}'s own cost is measured.
 */
class StockServicePerformanceTest {

	private static final int LARGE_UNIVERSE_SIZE = 10_000;
	private static final Duration SEARCH_BUDGET = Duration.ofMillis(500);

	@Test
	void unfilteredSearchOverLargeUniverseCompletesWithinBudget() {
		// Arrange — a large, deterministically generated universe served by
		// an in-memory fake provider, so the timing reflects only
		// StockService's own search/filter/sort cost
		List<Stock> universe = generateUniverse(LARGE_UNIVERSE_SIZE);
		FinancialDataProvider provider = new InMemoryFinancialDataProvider(universe);
		List<String> tickers = universe.stream().map(Stock::ticker).toList();
		var properties = new StockLensProperties(null,
				new StockLensProperties.Screener(tickers, Duration.ofMinutes(10)));
		StockService service = new StockService(provider, properties,
				Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC));

		// Act & Assert
		List<Stock> results = assertTimeout(SEARCH_BUDGET,
				() -> service.search(new StockQuery(null, null, null, SortBy.MARKET_CAP, Direction.DESC)));

		assertThat(results).hasSize(LARGE_UNIVERSE_SIZE);
	}

	/** Deterministic synthetic universe so the test is reproducible across runs. */
	private static List<Stock> generateUniverse(int size) {
		Random random = new Random(42);
		List<Stock> stocks = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			String ticker = "TCK" + i;
			double price = 1 + random.nextDouble() * 999;
			Double pe = random.nextInt(20) == 0 ? null : 1 + random.nextDouble() * 99;
			double marketCap = 1e8 + random.nextDouble() * 1e12;
			stocks.add(new Stock(ticker, "Company " + i, "Sector " + (i % 10), price, pe, marketCap, 0.01,
					Instant.EPOCH));
		}
		return stocks;
	}

	/** In-memory {@link FinancialDataProvider} fake — avoids per-ticker Mockito stubbing overhead. */
	private static final class InMemoryFinancialDataProvider implements FinancialDataProvider {

		private final Map<String, Stock> byTicker = new ConcurrentHashMap<>();

		InMemoryFinancialDataProvider(List<Stock> stocks) {
			stocks.forEach(stock -> byTicker.put(stock.ticker(), stock));
		}

		@Override
		public Optional<Stock> fetchStock(String ticker) {
			return Optional.ofNullable(byTicker.get(ticker));
		}
	}
}
