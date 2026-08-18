package com.stocklens.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.stocklens.config.StockLensProperties;
import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataException;
import com.stocklens.provider.FinancialDataProvider;
import com.stocklens.provider.RateLimitedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Screens an in-memory snapshot of the configured stock universe. The
 * snapshot is fetched lazily from the {@link FinancialDataProvider}, cached
 * for the configured TTL and served stale if a refresh fails — the screener
 * degrades gracefully instead of failing whenever the external API does.
 */
@Service
public class StockService {

	private static final Logger log = LoggerFactory.getLogger(StockService.class);

	private final FinancialDataProvider provider;
	private final StockLensProperties properties;
	private final Clock clock;
	private final AtomicReference<Snapshot> cache = new AtomicReference<>();

	public StockService(FinancialDataProvider provider, StockLensProperties properties, Clock clock) {
		this.provider = provider;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Screens the cached universe: free-text match, then filters, then sort.
	 *
	 * <p>Filter semantics worth knowing before you call this:
	 * <ul>
	 * <li>{@code maxPe} is <strong>inclusive</strong> — a P/E of exactly 29.5
	 * passes {@code maxPe=29.5}.</li>
	 * <li>A company whose filtered metric is {@code null} is <strong>excluded</strong>.
	 * "Unknown P/E" is not treated as "cheap".</li>
	 * <li>A negative P/E (a loss-making company) is kept, because it is a real
	 * reported value and is genuinely below any positive ceiling.</li>
	 * <li>Companies missing the <em>sorted</em> metric are placed last in both
	 * directions, and ties break on ticker, so the order is total and
	 * deterministic.</li>
	 * </ul>
	 *
	 * <p>Triggers a provider refresh only when the cache is empty or expired;
	 * see the class documentation for the degradation rules.
	 *
	 * <pre>{@code
	 * // "large caps trading under 30x earnings, cheapest first"
	 * List<Stock> value = service.search(new StockQuery(
	 *         null, 30.0, 500.0, SortBy.PE, Direction.ASC));
	 * }</pre>
	 *
	 * @param query validated search text, filters and sort order; the search
	 *              term may be {@code null} or blank to match everything, and
	 *              either filter may be {@code null} to skip it
	 * @return an immutable list of matching stocks, empty if nothing matches
	 * @throws DataUnavailableException if no snapshot is cached and the
	 *                                  provider cannot supply one
	 */
	public List<Stock> search(StockQuery query) {
		List<Stock> result = new ArrayList<>(currentSnapshot().stocks());
		if (query.search() != null && !query.search().isBlank()) {
			String needle = query.search().trim().toLowerCase(Locale.ROOT);
			result.removeIf(stock -> !matches(stock, needle));
		}
		if (query.maxPe() != null) {
			result.removeIf(stock -> stock.peRatio() == null || stock.peRatio() > query.maxPe());
		}
		if (query.minMarketCapBillions() != null) {
			double minUsd = query.minMarketCapBillions() * 1_000_000_000d;
			result.removeIf(stock -> stock.marketCap() == null || stock.marketCap() < minUsd);
		}
		Comparator<Stock> comparator = query.sortBy().comparator();
		if (query.direction() == StockQuery.Direction.DESC) {
			comparator = comparator.reversed();
		}
		// Companies missing the sorted metric go last in either direction, and
		// ties resolve by ticker so the order is deterministic.
		if (query.sortBy() != StockQuery.SortBy.NAME) {
			comparator = missingMetricLast(query.sortBy()).thenComparing(comparator);
		}
		result.sort(comparator.thenComparing(Stock::ticker));
		return List.copyOf(result);
	}

	/**
	 * Looks up one company in the screened universe.
	 *
	 * <p>Scoped to the configured universe, not to all of Finnhub: a valid
	 * symbol that is not in {@code stocklens.screener.tickers} is reported as
	 * not found, because the screener has no snapshot for it.
	 *
	 * <pre>{@code
	 * service.getByTicker(" msft ").name();   // "Microsoft Corp"
	 * }</pre>
	 *
	 * @param ticker exchange symbol; surrounding whitespace is trimmed and the
	 *               comparison ignores case
	 * @return the matching stock, never {@code null}
	 * @throws StockNotFoundException   if no screened company has that ticker
	 * @throws DataUnavailableException if no snapshot is cached and the
	 *                                  provider cannot supply one
	 */
	public Stock getByTicker(String ticker) {
		return currentSnapshot().stocks().stream()
			.filter(stock -> stock.ticker().equalsIgnoreCase(ticker.trim()))
			.findFirst()
			.orElseThrow(() -> new StockNotFoundException(ticker));
	}

	private static boolean matches(Stock stock, String needle) {
		return stock.ticker().toLowerCase(Locale.ROOT).contains(needle)
				|| stock.name().toLowerCase(Locale.ROOT).contains(needle);
	}

	private static Comparator<Stock> missingMetricLast(StockQuery.SortBy sortBy) {
		return Comparator.comparing(stock -> switch (sortBy) {
			case PRICE -> stock.price() == null;
			case PE -> stock.peRatio() == null;
			case MARKET_CAP -> stock.marketCap() == null;
			case NAME -> false;
		});
	}

	private Snapshot currentSnapshot() {
		Snapshot snapshot = cache.get();
		if (snapshot == null || isExpired(snapshot)) {
			return refresh();
		}
		return snapshot;
	}

	private synchronized Snapshot refresh() {
		Snapshot existing = cache.get();
		if (existing != null && !isExpired(existing)) {
			return existing; // another thread refreshed while we waited
		}
		List<Stock> fresh = new ArrayList<>();
		try {
			for (String ticker : properties.screener().tickers()) {
				try {
					provider.fetchStock(ticker).ifPresent(fresh::add);
				}
				catch (RateLimitedException e) {
					throw e; // stop immediately, further calls would also fail
				}
				catch (FinancialDataException e) {
					log.warn("Skipping {}: {}", ticker, e.getMessage());
				}
			}
		}
		catch (RateLimitedException e) {
			return staleOrUnavailable(existing, "the provider rate limit is exhausted");
		}
		if (fresh.isEmpty()) {
			return staleOrUnavailable(existing, "the provider returned no usable data");
		}
		Snapshot snapshot = new Snapshot(List.copyOf(fresh), clock.instant());
		cache.set(snapshot);
		return snapshot;
	}

	private Snapshot staleOrUnavailable(Snapshot stale, String reason) {
		if (stale != null) {
			log.warn("Refresh failed ({}); serving stale data from {}", reason, stale.fetchedAt());
			return stale;
		}
		throw new DataUnavailableException("Stock data is currently unavailable: " + reason);
	}

	private boolean isExpired(Snapshot snapshot) {
		return snapshot.fetchedAt()
			.plus(properties.screener().cacheTtl())
			.isBefore(clock.instant());
	}

	private record Snapshot(List<Stock> stocks, Instant fetchedAt) {
	}
}
