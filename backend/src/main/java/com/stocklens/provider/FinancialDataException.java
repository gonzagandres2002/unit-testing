package com.stocklens.provider;

/** The external financial API could not deliver usable data. */
public class FinancialDataException extends RuntimeException {

	public FinancialDataException(String message) {
		super(message);
	}

	public FinancialDataException(String message, Throwable cause) {
		super(message, cause);
	}
}
