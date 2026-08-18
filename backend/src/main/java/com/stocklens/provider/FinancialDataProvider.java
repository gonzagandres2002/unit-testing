package com.stocklens.provider;

import java.util.Optional;

import com.stocklens.domain.Stock;

/**
 * Abstraction over the external financial data source. A second provider can
 * be introduced by implementing this interface without touching the service
 * or web layers.
 *
 * <p>This is the seam that makes the screener testable: {@code StockService}
 * depends on this interface, so its 27 unit tests drive a Mockito stub and
 * never open a socket.
 *
 * <pre>{@code
 * // production
 * @Component
 * class FinnhubStockProvider implements FinancialDataProvider { ... }
 *
 * // test
 * when(provider.fetchStock("MSFT")).thenReturn(Optional.of(msft));
 * when(provider.fetchStock("BROKEN")).thenThrow(new FinancialDataException("HTTP 500"));
 * }</pre>
 *
 * @see com.stocklens.provider.finnhub.FinnhubStockProvider
 */
public interface FinancialDataProvider {

	/**
	 * Fetches current screening data for one ticker.
	 *
	 * <p>Implementations must distinguish three outcomes, because the service
	 * reacts differently to each:
	 * <table border="1">
	 * <caption>Outcome contract</caption>
	 * <tr><th>Outcome</th><th>Meaning</th><th>Service reaction</th></tr>
	 * <tr><td>{@code Optional} with a value</td><td>data retrieved</td>
	 *     <td>added to the snapshot</td></tr>
	 * <tr><td>{@code Optional.empty()}</td><td>provider answered fine but does
	 *     not know this ticker</td><td>silently omitted</td></tr>
	 * <tr><td>{@link RateLimitedException}</td><td>quota exhausted</td>
	 *     <td>whole refresh aborts</td></tr>
	 * <tr><td>{@link FinancialDataException}</td><td>any other failure</td>
	 *     <td>this ticker skipped, refresh continues</td></tr>
	 * </table>
	 *
	 * <p>An unknown ticker is deliberately <em>not</em> an exception: it is an
	 * expected outcome of screening a configured universe, not an error.
	 *
	 * @param ticker exchange symbol to fetch, e.g. {@code "MSFT"}
	 * @return the stock, or empty if the provider answered correctly but does
	 *         not know the ticker or lacks the essential data (name, price)
	 * @throws RateLimitedException   if the provider rejected the call because
	 *                                the rate limit is exhausted
	 * @throws FinancialDataException for any other failure (timeout, HTTP
	 *                                error, unreachable host, invalid payload)
	 */
	Optional<Stock> fetchStock(String ticker);
}
