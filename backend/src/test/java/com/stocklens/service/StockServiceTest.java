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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockService}: screening logic (search, filter, sort)
 * and caching / degradation behavior, with the external provider mocked and
 * the clock injected so no test depends on real time.
 *
 * <p>Every test follows the Arrange-Act-Assert (AAA) pattern with the three
 * phases explicitly marked. Tests 1-4 cover happy paths; tests 5-10 cover
 * boundaries and unhappy paths, where the interesting bugs live.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

	private static final Duration TTL = Duration.ofMinutes(10);

	private static final Stock MSFT = stock("MSFT", "Microsoft", 512.30, 29.5, 3_100e9);
	private static final Stock GOOGL = stock("GOOGL", "Alphabet", 201.10, 27.2, 2_400e9);
	private static final Stock AAPL = stock("AAPL", "Apple", 230.50, 30.1, 3_500e9);
	/** No P/E and no market cap — the provider had no data for those metrics. */
	private static final Stock NODATA = stock("NODATA", "NoData Corp", 10.0, null, null);
	/** Negative P/E — a loss-making company, reported that way by the provider. */
	private static final Stock LOSSCO = stock("LOSSCO", "Lossmaker Inc", 5.0, -8.2, 1e9);

	@Mock
	private FinancialDataProvider provider;

	private final MutableClock clock = new MutableClock();

	private StockService service;

	// ---------------------------------------------------------------------
	// Happy paths
	// ---------------------------------------------------------------------

	@Test
	void searchWithoutFiltersReturnsWholeUniverse() {
		// Arrange
		serviceWith(MSFT, GOOGL, AAPL);

		// Act
		List<Stock> results = service.search(query(null, null, null));

		// Assert
		assertThat(tickers(results)).containsExactlyInAnyOrder("MSFT", "GOOGL", "AAPL");
	}

	@Test
	void textSearchMatchesCompanyNameIgnoringCase() {
		// Arrange
		serviceWith(MSFT, GOOGL, AAPL);

		// Act
		List<Stock> results = service.search(query("micRO", null, null));

		// Assert — "micRO" matches the name "Microsoft", nothing else
		assertThat(tickers(results)).containsExactly("MSFT");
	}

	@Test
	void maxPeBoundaryIsInclusive() {
		// Arrange — MSFT's P/E is exactly 29.5, AAPL's is 30.1
		serviceWith(MSFT, AAPL);

		// Act
		List<Stock> results = service.search(query(null, 29.5, null));

		// Assert — a P/E exactly on the limit passes the filter
		assertThat(tickers(results)).containsExactly("MSFT");
	}

	@Test
	void getByTickerIgnoresCaseAndWhitespace() {
		// Arrange
		serviceWith(MSFT, GOOGL);

		// Act
		Stock result = service.getByTicker(" msft ");

		// Assert
		assertThat(result.name()).isEqualTo("Microsoft");
	}

	// ---------------------------------------------------------------------
	// Boundaries and unhappy paths
	// ---------------------------------------------------------------------

	@Test
	void companyWithNullPeIsExcludedByPeFilter() {
		// Arrange — NODATA has no reported P/E at all
		serviceWith(MSFT, NODATA);

		// Act
		List<Stock> results = service.search(query(null, 100.0, null));

		// Assert — "unknown P/E" is not treated as "cheap"
		assertThat(tickers(results)).containsExactly("MSFT");
	}

	@Test
	void negativePeStillPassesMaxPeFilter() {
		// Arrange — LOSSCO's P/E is -8.2, AAPL's is 30.1
		serviceWith(LOSSCO, AAPL);

		// Act
		List<Stock> results = service.search(query(null, 30.0, null));

		// Assert — a real reported loss is genuinely below any positive ceiling
		assertThat(tickers(results)).containsExactly("LOSSCO");
	}

	@Test
	void companyMissingTheSortedMetricGoesLastInBothDirections() {
		// Arrange — NODATA has no market cap to sort by
		serviceWith(MSFT, GOOGL, NODATA);

		// Act — same sort field, both directions
		List<Stock> ascending = service.search(sorted(SortBy.MARKET_CAP, Direction.ASC));
		List<Stock> descending = service.search(sorted(SortBy.MARKET_CAP, Direction.DESC));

		// Assert — reversing the order must not promote the company with no data
		assertThat(tickers(ascending)).containsExactly("GOOGL", "MSFT", "NODATA");
		assertThat(tickers(descending)).containsExactly("MSFT", "GOOGL", "NODATA");
	}

	@Test
	void unknownTickerThrowsStockNotFound() {
		// Arrange — ZZZZ is not part of the screened universe
		serviceWith(MSFT, GOOGL);

		// Act + Assert — the call under test lives inside the assertion
		assertThatThrownBy(() -> service.getByTicker("ZZZZ"))
			.isInstanceOf(StockNotFoundException.class)
			.hasMessageContaining("ZZZZ");
	}

	@Test
	void rateLimitWithColdCacheThrowsDataUnavailable() {
		// Arrange — nothing cached yet and the provider rejects every call
		serviceForTickers(List.of("MSFT", "GOOGL"));
		when(provider.fetchStock(anyString())).thenThrow(new RateLimitedException("HTTP 429"));

		// Act + Assert — with no stale snapshot to fall back on, the service fails
		assertThatThrownBy(() -> service.search(query(null, null, null)))
			.isInstanceOf(DataUnavailableException.class)
			.hasMessageContaining("rate limit");
	}

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

	// ---------------------------------------------------------------------
	// Fixture helpers — keep the Arrange phase of each test to one line
	// ---------------------------------------------------------------------

	private static Stock stock(String ticker, String name, Double price, Double pe, Double marketCap) {
		return new Stock(ticker, name, "Sector", price, pe, marketCap, null, Instant.EPOCH);
	}

	/** Configures a universe of the given stocks and stubs the provider with them. */
	private StockService serviceWith(Stock... stocks) {
		for (Stock stock : stocks) {
			when(provider.fetchStock(stock.ticker())).thenReturn(Optional.of(stock));
		}
		return serviceForTickers(Arrays.stream(stocks).map(Stock::ticker).toList());
	}

	/** Builds the service for the given universe without stubbing any fetch. */
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

	/**
	 * A fake clock the test can move forward at will, so cache-expiry behavior
	 * is tested in microseconds instead of real minutes.
	 */
	private static final class MutableClock extends Clock {

		private Instant instant = Instant.parse("2026-08-17T12:00:00Z");

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
