package com.stocklens.provider;

/**
 * The external financial API could not deliver usable data.
 *
 * <p>Raised for a timeout, an unreachable host, an HTTP error, an unparseable
 * payload or a missing API key. Callers treat it as <em>skippable</em>: one
 * failing ticker is logged and left out of the snapshot while the rest of the
 * refresh continues. Its subclass {@link RateLimitedException} is the
 * exception to that rule.
 *
 * <p>Provider-specific exceptions are translated into this type at the
 * boundary, so no layer above {@link FinancialDataProvider} depends on which
 * HTTP client or vendor is in use.
 */
public class FinancialDataException extends RuntimeException {

	/**
	 * Builds the exception without an underlying cause.
	 *
	 * @param message what could not be obtained and for which ticker
	 */
	public FinancialDataException(String message) {
		super(message);
	}

	/**
	 * Preferred when translating a lower-level failure, since the cause keeps
	 * the original stack trace available for diagnosis.
	 *
	 * @param message domain-level description, e.g. "Finnhub unreachable or
	 *                timed out for MSFT"
	 * @param cause   the underlying failure, e.g. a {@code SocketTimeoutException}
	 */
	public FinancialDataException(String message, Throwable cause) {
		super(message, cause);
	}
}
