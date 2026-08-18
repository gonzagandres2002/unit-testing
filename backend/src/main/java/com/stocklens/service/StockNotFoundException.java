package com.stocklens.service;

/**
 * The requested ticker is not part of the screened universe.
 *
 * <p>Mapped to {@code 404 Not Found} by
 * {@link com.stocklens.web.GlobalExceptionHandler}. Unchecked so that it can
 * travel from the service to the handler without every intermediate layer
 * declaring it.
 */
public class StockNotFoundException extends RuntimeException {

	/**
	 * Builds the exception with a message naming the missing ticker.
	 *
	 * @param ticker the symbol that was requested; embedded in the message,
	 *               which is returned to the client as the problem detail
	 */
	public StockNotFoundException(String ticker) {
		super("No stock found for ticker '" + ticker + "'");
	}
}
