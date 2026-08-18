package com.stocklens.provider;

/**
 * The external financial API rejected the call because the request quota is
 * exhausted (HTTP 429). Kept separate from {@link FinancialDataException} so
 * callers can stop issuing further calls instead of retrying per ticker.
 */
public class RateLimitedException extends FinancialDataException {

	/**
	 * Builds the exception naming the provider that rejected the call.
	 *
	 * @param message which provider rejected the call and why
	 */
	public RateLimitedException(String message) {
		super(message);
	}
}
