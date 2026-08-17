package com.stocklens.provider;

import java.util.Optional;

import com.stocklens.domain.Stock;

/**
 * Abstraction over the external financial data source. A second provider can
 * be introduced by implementing this interface without touching the service
 * or web layers.
 */
public interface FinancialDataProvider {

	/**
	 * Fetches current screening data for one ticker.
	 *
	 * @return the stock, or empty if the provider answered correctly but does
	 *         not know the ticker or lacks the essential data (name, price)
	 * @throws RateLimitedException   if the provider rejected the call because
	 *                                the rate limit is exhausted
	 * @throws FinancialDataException for any other failure (timeout, HTTP
	 *                                error, unreachable host, invalid payload)
	 */
	Optional<Stock> fetchStock(String ticker);
}
