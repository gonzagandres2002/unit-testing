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
