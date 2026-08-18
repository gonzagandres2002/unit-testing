package com.stocklens.service;

import java.util.Comparator;

import com.stocklens.domain.Stock;

/**
 * A validated screener query: free-text search on name/ticker, optional
 * filters and a sort order.
 *
 * <p>This is the boundary type between the web layer and
 * {@link StockService}. By the time an instance exists, the raw strings from
 * the HTTP request have already been parsed and rejected if malformed, so the
 * service never deals with user input.
 *
 * <pre>{@code
 * // everything, largest first (the API defaults)
 * new StockQuery(null, null, null, SortBy.MARKET_CAP, Direction.DESC);
 *
 * // "Apple or Alphabet under 30x earnings, above $500B"
 * new StockQuery("a", 30.0, 500.0, SortBy.PE, Direction.ASC);
 * }</pre>
 *
 * @param search               case-insensitive substring matched against
 *                             ticker and name; {@code null} or blank matches
 *                             everything
 * @param maxPe                inclusive upper P/E bound; {@code null} to skip
 *                             the filter
 * @param minMarketCapBillions inclusive lower bound in <strong>billions</strong>
 *                             of USD — "Market Cap &gt; $10B" is {@code 10.0},
 *                             converted to plain USD inside the service so
 *                             the API stays human-friendly; {@code null} to
 *                             skip the filter
 * @param sortBy               metric to order by; never {@code null}
 * @param direction            ascending or descending; never {@code null}
 */
public record StockQuery(
		String search,
		Double maxPe,
		Double minMarketCapBillions,
		SortBy sortBy,
		Direction direction) {

	/**
	 * Sortable fields. Each constant carries its own {@link Comparator}, so
	 * the service asks the enum for a comparator instead of branching on the
	 * field name.
	 *
	 * <p>All metric comparators sort {@code null} last in ascending order;
	 * the service additionally re-applies nulls-last after reversing, so
	 * companies missing the metric stay at the bottom in both directions.
	 */
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

		/**
		 * Parses the {@code sortBy} query parameter.
		 *
		 * <pre>{@code
		 * SortBy.from("marketCap");    // MARKET_CAP
		 * SortBy.from("MARKET_CAP");   // MARKET_CAP — snake_case also accepted
		 * SortBy.from("volume");       // throws IllegalArgumentException
		 * }</pre>
		 *
		 * @param value raw parameter; case and surrounding whitespace are
		 *              ignored
		 * @return the matching constant
		 * @throws IllegalArgumentException if unrecognised, including for
		 *                                  {@code null}. The message lists the
		 *                                  valid values and is surfaced
		 *                                  verbatim in the 400 response
		 */
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

	/** Sort direction for the selected {@link SortBy} field. */
	public enum Direction {

		ASC, DESC;

		/**
		 * Parses the {@code order} query parameter.
		 *
		 * @param value {@code "asc"} or {@code "desc"}; case and surrounding
		 *              whitespace are ignored
		 * @return the matching constant
		 * @throws IllegalArgumentException if unrecognised, including for
		 *                                  {@code null} → {@code 400}
		 */
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
