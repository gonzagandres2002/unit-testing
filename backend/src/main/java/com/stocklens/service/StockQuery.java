package com.stocklens.service;

import java.util.Comparator;

import com.stocklens.domain.Stock;

/**
 * A validated screener query: free-text search on name/ticker, optional
 * filters and a sort order. {@code minMarketCapBillions} is expressed in
 * billions of USD for usability ("Market Cap > $10B" is {@code 10}).
 */
public record StockQuery(
		String search,
		Double maxPe,
		Double minMarketCapBillions,
		SortBy sortBy,
		Direction direction) {

	public enum SortBy {

		NAME(Comparator.comparing(Stock::name, String.CASE_INSENSITIVE_ORDER)),
		PRICE(byMetric(Stock::price)),
		PE(byMetric(Stock::peRatio)),
		MARKET_CAP(byMetric(Stock::marketCap));

		private final Comparator<Stock> comparator;

		SortBy(Comparator<Stock> comparator) {
			this.comparator = comparator;
		}

		Comparator<Stock> comparator() {
			return comparator;
		}

		private static Comparator<Stock> byMetric(java.util.function.Function<Stock, Double> metric) {
			return Comparator.comparing(metric, Comparator.nullsLast(Comparator.naturalOrder()));
		}

		public static SortBy from(String value) {
			return switch (value == null ? "" : value.trim().toLowerCase()) {
				case "name" -> NAME;
				case "price" -> PRICE;
				case "pe" -> PE;
				case "marketcap", "market_cap" -> MARKET_CAP;
				default -> throw new IllegalArgumentException(
						"Unknown sortBy '" + value + "'; expected one of: name, price, pe, marketCap");
			};
		}
	}

	public enum Direction {

		ASC, DESC;

		public static Direction from(String value) {
			return switch (value == null ? "" : value.trim().toLowerCase()) {
				case "asc" -> ASC;
				case "desc" -> DESC;
				default -> throw new IllegalArgumentException(
						"Unknown order '" + value + "'; expected asc or desc");
			};
		}
	}
}
