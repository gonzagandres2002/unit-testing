package com.stocklens.web;

import java.util.List;

import com.stocklens.domain.Stock;
import com.stocklens.service.StockQuery;
import com.stocklens.service.StockService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.validation.annotation.Validated;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the stock screener.
 *
 * <p>The controller is intentionally thin: it validates and parses query
 * parameters, delegates to {@link StockService}, and returns domain objects
 * for Jackson to serialize. It contains no {@code try}/{@code catch} — every
 * failure is mapped to an RFC 9457 problem detail by
 * {@link GlobalExceptionHandler}.
 *
 * <p>Full HTTP reference with request/response examples:
 * <a href="../../../../../../../../docs/API.md">docs/API.md</a>.
 */
@RestController
@RequestMapping(path = "/api/stocks", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "stocks")
public class StockController {

	private final StockService stockService;

	public StockController(StockService stockService) {
		this.stockService = stockService;
	}

	/**
	 * {@code GET /api/stocks} — screens the universe.
	 *
	 * <pre>{@code
	 * GET /api/stocks?q=micro&maxPe=30&minMarketCap=500&sortBy=pe&order=asc
	 *
	 * 200 OK
	 * [ { "ticker": "MSFT", "name": "Microsoft Corp", "sector": "Technology",
	 *     "price": 512.3, "peRatio": 29.5, "marketCap": 3.1E12,
	 *     "dividendYield": 0.71, "lastUpdated": "2026-08-17T12:00:00Z" } ]
	 * }</pre>
	 *
	 * @param search       {@code q} — case-insensitive substring matched
	 *                     against ticker <em>and</em> company name; omit or
	 *                     leave blank to return the whole universe
	 * @param maxPe        upper P/E bound, inclusive; must be {@code > 0}.
	 *                     Companies with no reported P/E are excluded
	 * @param minMarketCap lower market-cap bound in <strong>billions</strong>
	 *                     of USD ({@code 500} means $500B); must be
	 *                     {@code >= 0}. Companies with no reported market cap
	 *                     are excluded
	 * @param sortBy       one of {@code marketCap}, {@code pe}, {@code price},
	 *                     {@code name}; defaults to {@code marketCap}
	 * @param order        {@code asc} or {@code desc}; defaults to {@code desc}
	 * @return matching stocks, possibly empty; an empty result is {@code 200}
	 *         with {@code []}, never {@code 404}
	 * @throws IllegalArgumentException if {@code sortBy} or {@code order} is
	 *                                  not a recognised value → {@code 400}
	 */
	@Operation(summary = "Screen the universe", description = """
			Applies free-text search, then filters, then sort.

			Three filter rules worth knowing:

			- `maxPe` is **inclusive** — a P/E of exactly 29.5 passes `maxPe=29.5`.
			- A company whose filtered metric is `null` is **excluded**, not treated
			  as passing. "Unknown P/E" is not "cheap".
			- A **negative** P/E (a loss-making company) is **kept**, since it is
			  genuinely below any positive ceiling.

			Companies missing the *sorted* metric are placed last in both directions,
			and ties break on ticker, so ordering is deterministic across calls.

			An empty result is `200` with `[]`, never `404`.
			""")
	@GetMapping
	public List<Stock> search(
			@RequestParam(name = "q", required = false) String search,
			@RequestParam(required = false) @Positive Double maxPe,
			@RequestParam(required = false) @PositiveOrZero Double minMarketCap,
			@RequestParam(defaultValue = "marketCap") String sortBy,
			@RequestParam(defaultValue = "desc") String order) {
		return stockService.search(new StockQuery(
				search,
				maxPe,
				minMarketCap,
				StockQuery.SortBy.from(sortBy),
				StockQuery.Direction.from(order)));
	}

	/**
	 * {@code GET /api/stocks/{ticker}} — detail for one screened company.
	 *
	 * <pre>{@code
	 * GET /api/stocks/MSFT      → 200 { "ticker": "MSFT", ... }
	 * GET /api/stocks/ZZZZ      → 404 { "status": 404, "detail": "No stock found for ticker 'ZZZZ'" }
	 * GET /api/stocks/MS%20FT!  → 400 (fails the pattern below)
	 * }</pre>
	 *
	 * @param ticker exchange symbol, matched case-insensitively. The pattern
	 *               rejects malformed input before it reaches the service: a
	 *               leading letter followed by up to nine letters, digits,
	 *               dots or hyphens
	 * @return the matching stock
	 * @throws com.stocklens.service.StockNotFoundException if the ticker is
	 *         well-formed but absent from the screened universe → {@code 404}
	 */
	@Operation(summary = "Look up one company", description = """
			Returns one company from the screened universe.

			Scoped to the configured universe, not to all of Finnhub: a real symbol
			that is not in `stocklens.screener.tickers` returns `404`, because the
			screener holds no snapshot for it.

			The ticker is matched case-insensitively.
			""")
	@GetMapping("/{ticker}")
	public Stock byTicker(@PathVariable @Pattern(regexp = "[A-Za-z][A-Za-z0-9.\\-]{0,9}") String ticker) {
		return stockService.getByTicker(ticker);
	}
}
