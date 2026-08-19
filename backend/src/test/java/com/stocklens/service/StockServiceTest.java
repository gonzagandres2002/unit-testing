package com.stocklens.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.stocklens.config.StockLensProperties;
import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataProvider;
import com.stocklens.provider.RateLimitedException;
import com.stocklens.service.StockQuery.Direction;
import com.stocklens.service.StockQuery.SortBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the screener logic (search, filter, sort) and the caching /
 * degradation behavior, with the external provider fully mocked. Scoped to ten
 * tests that cover the critical behavior of {@link StockService}, the core
 * business-logic class: its two public methods plus the filter, sort, cache
 * and graceful-degradation branches most likely to break.
 *
 * <p>Every test follows the Arrange-Act-Assert (AAA) pattern with the three
 * phases explicitly separated: Arrange builds the fixture (mock stubbing,
 * service construction), Act performs the single operation under test, and
 * Assert verifies the outcome.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

	private static final Duration TTL = Duration.ofMinutes(10);

	private static final Stock MSFT = stock("MSFT", "Microsoft", 512.30, 29.5, 3_100e9, 0.7);
	private static final Stock GOOGL = stock("GOOGL", "Alphabet", 201.10, 27.2, 2_400e9, 0.4);
	private static final Stock AAPL = stock("AAPL", "Apple", 230.50, 30.1, 3_500e9, 0.5);
	/** No P/E and no market cap — company without positive earnings/data. */
	private static final Stock NODATA = stock("NODATA", "NoData Corp", 10.0, null, null, null);

	@Mock
	private FinancialDataProvider provider;

	private final MutableClock clock = new MutableClock();

	private StockService service;

	private static Stock stock(String ticker, String name, Double price, Double pe, Double marketCap, Double yield) {
		return new Stock(ticker, name, "Sector", price, pe, marketCap, yield, Instant.EPOCH);
	}

	/** Configures a universe of the given stocks and stubs the provider with them. */
	private StockService serviceWith(Stock... stocks) {
		for (Stock stock : stocks) {
			when(provider.fetchStock(stock.ticker())).thenReturn(Optional.of(stock));
		}
		List<String> tickers = Arrays.stream(stocks).map(Stock::ticker).toList();
		return serviceForTickers(tickers);
	}

	private StockService serviceForTickers(List<String> tickers) {
		var properties = new StockLensProperties(null, new StockLensProperties.Screener(tickers, TTL));
		service = new StockService(provider, properties, clock);
		return service;
	}

	private static StockQuery query(String search, Double maxPe, Double minMarketCapBillions) {
		return new StockQuery(search, maxPe, minMarketCapBillions, SortBy.MARKET_CAP, Direction.DESC);
	}

	private static StockQuery sorted(SortBy sortBy, Direction direction) {
		return new StockQuery(null, null, null, sortBy, direction);
	}

	private static List<String> tickers(List<Stock> stocks) {
		return stocks.stream().map(Stock::ticker).toList();
	}

	@Nested
	class Search {

		@Test
		void findsExistingCompanyByPartialNameIgnoringCase() {
			// Arrange
			serviceWith(MSFT, GOOGL, AAPL);

			// Act
			List<Stock> results = service.search(query("micRO", null, null));

			// Assert
			assertThat(tickers(results)).containsExactly("MSFT");
		}
	}

	@Nested
	class Filtering {

		@Test
		void maxPeExcludesCompaniesWithMissingPe() {
			// Arrange
			serviceWith(MSFT, NODATA);

			// Act
			List<Stock> results = service.search(query(null, 100.0, null));

			// Assert
			assertThat(tickers(results)).containsExactly("MSFT");
		}

		@Test
		void minMarketCapZeroKeepsAllCompaniesWithData() {
			// Arrange
			serviceWith(MSFT, GOOGL, NODATA);

			// Act
			List<Stock> results = service.search(query(null, null, 0.0));

			// Assert
			assertThat(tickers(results)).containsExactlyInAnyOrder("MSFT", "GOOGL");
		}
	}

	@Nested
	class Sorting {

		@Test
		void sortsByMarketCapDescending() {
			// Arrange
			serviceWith(MSFT, GOOGL, AAPL);

			// Act
			List<Stock> results = service.search(sorted(SortBy.MARKET_CAP, Direction.DESC));

			// Assert
			assertThat(tickers(results)).containsExactly("AAPL", "MSFT", "GOOGL");
		}

		@Test
		void sortsByPeAscending() {
			// Arrange
			serviceWith(MSFT, GOOGL, AAPL);

			// Act
			List<Stock> results = service.search(sorted(SortBy.PE, Direction.ASC));

			// Assert
			assertThat(tickers(results)).containsExactly("GOOGL", "MSFT", "AAPL");
		}

		@Test
		void companiesMissingTheSortedMetricGoLastAscending() {
			// Arrange
			serviceWith(MSFT, NODATA, GOOGL);

			// Act
			List<Stock> results = service.search(sorted(SortBy.PE, Direction.ASC));

			// Assert
			assertThat(tickers(results)).endsWith("NODATA");
		}
	}

	@Nested
	class CachingAndResilience {

		@Test
		void secondSearchWithinTtlDoesNotCallProviderAgain() {
			// Arrange
			serviceWith(MSFT, GOOGL);

			// Act
			service.search(query(null, null, null));
			service.search(query("micro", null, null));

			// Assert
			verify(provider, times(2)).fetchStock(anyString());
		}

		@Test
		void expiredCacheIsRefreshedFromProvider() {
			// Arrange
			serviceWith(MSFT, GOOGL);
			service.search(query(null, null, null));
			clock.advance(TTL.plusSeconds(1));

			// Act
			service.search(query(null, null, null));

			// Assert
			verify(provider, times(4)).fetchStock(anyString());
		}

		@Test
		void rateLimitAfterSuccessfulFetchServesStaleData() {
			// Arrange
			when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT));
			serviceForTickers(List.of("MSFT"));
			service.search(query(null, null, null));
			clock.advance(TTL.plusSeconds(1));
			when(provider.fetchStock("MSFT")).thenThrow(new RateLimitedException("429"));

			// Act
			List<Stock> results = service.search(query(null, null, null));

			// Assert
			assertThat(tickers(results)).containsExactly("MSFT");
		}
	}

	@Nested
	class SingleStockLookup {

		@Test
		void findsStockByTickerIgnoringCase() {
			// Arrange
			serviceWith(MSFT, GOOGL);

			// Act
			Stock result = service.getByTicker(" msft ");

			// Assert
			assertThat(result.name()).isEqualTo("Microsoft");
		}
	}

	/** Deterministic, manually advanced clock for TTL tests. */
	private static final class MutableClock extends Clock {

		private Instant instant = Instant.parse("2026-08-17T12:00:00Z");

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
