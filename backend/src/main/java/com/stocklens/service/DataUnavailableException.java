package com.stocklens.service;

/**
 * No cached snapshot exists and the external provider cannot supply one.
 *
 * <p>This is the only provider failure the user ever sees. A failed refresh
 * with a warm cache is served stale and silently; this exception means the
 * cache was <em>cold</em> too, so there is genuinely nothing to return.
 * Mapped to {@code 503 Service Unavailable}, which correctly signals a
 * transient condition worth retrying.
 */
public class DataUnavailableException extends RuntimeException {

	/**
	 * Builds the exception with a message explaining why nothing can be served.
	 *
	 * @param message human-readable cause, e.g. "Stock data is currently
	 *                unavailable: the provider rate limit is exhausted";
	 *                returned to the client as the problem detail
	 */
	public DataUnavailableException(String message) {
		super(message);
	}
}
