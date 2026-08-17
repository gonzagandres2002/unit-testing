package com.stocklens.provider;

/**
 * The external financial API rejected the call because the request quota is
 * exhausted (HTTP 429). Kept separate from {@link FinancialDataException} so
 * callers can stop issuing further calls instead of retrying per ticker.
 */
public class RateLimitedException extends FinancialDataException {

	public RateLimitedException(String message) {
		super(message);
	}
}
