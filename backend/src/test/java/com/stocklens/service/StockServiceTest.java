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
import com.stocklens.provider.FinancialDataException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the screener logic (search, filter, sort) and the caching /
 * degradation behavior, with the external provider fully mocked.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

	private static final Duration TTL = Duration.ofMinutes(10);

	private static final Stock MSFT = stock("MSFT", "Microsoft", 512.30, 29.5, 3_100e9, 0.7);
	private static final Stock GOOGL = stock("GOOGL", "Alphabet", 201.10, 27.2, 2_400e9, 0.4);
	private static final Stock AAPL = stock("AAPL", "Apple", 230.50, 30.1, 3_500e9, 0.5);
	/** No P/E and no market cap — company without positive earnings/data. */
	private static final Stock NODATA = stock("NODATA", "NoData Corp", 10.0, null, null, null);
	/** Negative P/E — provider reported a loss-making company that way. */
	private static final Stock LOSSCO = stock("LOSSCO", "Lossmaker Inc", 5.0, -8.2, 1e9, null);

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
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(query("micRO", null, null)))).containsExactly("MSFT");
		}

		@Test
		void findsExistingCompanyByTickerIgnoringCase() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(query("aapl", null, null)))).containsExactly("AAPL");
		}

		@Test
		void nonexistentCompanyYieldsEmptyResult() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(service.search(query("Netflix", null, null))).isEmpty();
		}

		@Test
		void emptyAndBlankSearchReturnEverything() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(service.search(query(null, null, null))).hasSize(3);
			assertThat(service.search(query("   ", null, null))).hasSize(3);
		}
	}

	@Nested
	class Filtering {

		@Test
		void maxPeKeepsOnlyCheaperCompaniesAndTreatsBoundaryAsInclusive() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(query(null, 29.5, null))))
				.containsExactlyInAnyOrder("MSFT", "GOOGL");
		}

		@Test
		void maxPeExcludesCompaniesWithMissingPe() {
			serviceWith(MSFT, NODATA);
			assertThat(tickers(service.search(query(null, 100.0, null)))).containsExactly("MSFT");
		}

		@Test
		void maxPeKeepsNegativePeCompanies() {
			serviceWith(MSFT, LOSSCO);
			assertThat(tickers(service.search(query(null, 30.0, null))))
				.containsExactlyInAnyOrder("MSFT", "LOSSCO");
		}

		@Test
		void minMarketCapZeroKeepsAllCompaniesWithData() {
			serviceWith(MSFT, GOOGL, NODATA);
			assertThat(tickers(service.search(query(null, null, 0.0))))
				.containsExactlyInAnyOrder("MSFT", "GOOGL");
		}

		@Test
		void veryLargeMinMarketCapYieldsEmptyResult() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(service.search(query(null, null, 1e12))).isEmpty();
		}

		@Test
		void filtersCombineWithSearch() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(service.search(query("a", 28.0, null)))
				.extracting(Stock::ticker)
				.containsExactly("GOOGL"); // "a" matches Alphabet + Apple, P/E cuts Apple
		}
	}

	@Nested
	class Sorting {

		@Test
		void sortsByMarketCapDescending() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(sorted(SortBy.MARKET_CAP, Direction.DESC))))
				.containsExactly("AAPL", "MSFT", "GOOGL");
		}

		@Test
		void sortsByPeAscending() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(sorted(SortBy.PE, Direction.ASC))))
				.containsExactly("GOOGL", "MSFT", "AAPL");
		}

		@Test
		void sortsByPriceDescending() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(sorted(SortBy.PRICE, Direction.DESC))))
				.containsExactly("MSFT", "AAPL", "GOOGL");
		}

		@Test
		void sortsByNameAscending() {
			serviceWith(MSFT, GOOGL, AAPL);
			assertThat(tickers(service.search(sorted(SortBy.NAME, Direction.ASC))))
				.containsExactly("GOOGL", "AAPL", "MSFT"); // Alphabet, Apple, Microsoft
		}

		@Test
		void companiesMissingTheSortedMetricGoLastInBothDirections() {
			serviceWith(MSFT, NODATA, GOOGL);
			assertThat(tickers(service.search(sorted(SortBy.PE, Direction.ASC)))).endsWith("NODATA");
			assertThat(tickers(service.search(sorted(SortBy.PE, Direction.DESC)))).endsWith("NODATA");
		}
	}

	@Nested
	class CachingAndResilience {

		@Test
		void secondSearchWithinTtlDoesNotCallProviderAgain() {
			serviceWith(MSFT, GOOGL);
			service.search(query(null, null, null));
			service.search(query("micro", null, null));
			verify(provider, times(2)).fetchStock(anyString());
		}

		@Test
		void expiredCacheIsRefreshedFromProvider() {
			serviceWith(MSFT, GOOGL);
			service.search(query(null, null, null));
			clock.advance(TTL.plusSeconds(1));
			service.search(query(null, null, null));
			verify(provider, times(4)).fetchStock(anyString());
		}

		@Test
		void tickerThatFailsIsSkippedAndOthersAreServed() {
			when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT));
			when(provider.fetchStock("BROKEN")).thenThrow(new FinancialDataException("HTTP 500"));
			when(provider.fetchStock("GOOGL")).thenReturn(Optional.of(GOOGL));
			serviceForTickers(List.of("MSFT", "BROKEN", "GOOGL"));

			assertThat(tickers(service.search(query(null, null, null))))
				.containsExactlyInAnyOrder("MSFT", "GOOGL");
		}

		@Test
		void unknownTickerInUniverseIsSimplyAbsent() {
			when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT));
			when(provider.fetchStock("GHOST")).thenReturn(Optional.empty());
			serviceForTickers(List.of("MSFT", "GHOST"));

			assertThat(tickers(service.search(query(null, null, null)))).containsExactly("MSFT");
		}

		@Test
		void rateLimitWithoutCacheMeansDataUnavailable() {
			when(provider.fetchStock(anyString())).thenThrow(new RateLimitedException("429"));
			serviceForTickers(List.of("MSFT", "GOOGL"));

			assertThatThrownBy(() -> service.search(query(null, null, null)))
				.isInstanceOf(DataUnavailableException.class);
			// The refresh must stop at the first rate-limit error instead of
			// hammering the provider for every remaining ticker.
			verify(provider, times(1)).fetchStock(anyString());
		}

		@Test
		void rateLimitAfterSuccessfulFetchServesStaleData() {
			when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT));
			serviceForTickers(List.of("MSFT"));
			service.search(query(null, null, null));

			clock.advance(TTL.plusSeconds(1));
			when(provider.fetchStock("MSFT")).thenThrow(new RateLimitedException("429"));

			assertThat(tickers(service.search(query(null, null, null)))).containsExactly("MSFT");
		}

		@Test
		void totalProviderFailureWithoutCacheMeansDataUnavailable() {
			when(provider.fetchStock(anyString())).thenThrow(new FinancialDataException("down"));
			serviceForTickers(List.of("MSFT", "GOOGL"));

			assertThatThrownBy(() -> service.search(query(null, null, null)))
				.isInstanceOf(DataUnavailableException.class);
		}

		@Test
		void totalProviderFailureAfterSuccessfulFetchServesStaleData() {
			when(provider.fetchStock("MSFT")).thenReturn(Optional.of(MSFT));
			serviceForTickers(List.of("MSFT"));
			service.search(query(null, null, null));

			clock.advance(TTL.plusSeconds(1));
			when(provider.fetchStock("MSFT")).thenThrow(new FinancialDataException("down"));

			assertThat(tickers(service.search(query(null, null, null)))).containsExactly("MSFT");
		}
	}

	@Nested
	class SingleStockLookup {

		@Test
		void findsStockByTickerIgnoringCase() {
			serviceWith(MSFT, GOOGL);
			assertThat(service.getByTicker(" msft ").name()).isEqualTo("Microsoft");
		}

		@Test
		void unknownTickerThrowsNotFound() {
			serviceWith(MSFT);
			assertThatThrownBy(() -> service.getByTicker("ZZZZ"))
				.isInstanceOf(StockNotFoundException.class);
		}
	}

	@Nested
	class QueryParsing {

		@Test
		void sortByAcceptsKnownValuesCaseInsensitively() {
			assertThat(SortBy.from("marketCap")).isEqualTo(SortBy.MARKET_CAP);
			assertThat(SortBy.from("MARKET_CAP")).isEqualTo(SortBy.MARKET_CAP);
			assertThat(SortBy.from("pe")).isEqualTo(SortBy.PE);
			assertThat(SortBy.from("Price")).isEqualTo(SortBy.PRICE);
			assertThat(SortBy.from("name")).isEqualTo(SortBy.NAME);
		}

		@Test
		void invalidSortByAndOrderAreRejected() {
			assertThatThrownBy(() -> SortBy.from("volume")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> SortBy.from(null)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> Direction.from("sideways")).isInstanceOf(IllegalArgumentException.class);
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
