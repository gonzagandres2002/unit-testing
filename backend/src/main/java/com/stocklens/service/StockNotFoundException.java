package com.stocklens.service;

public class StockNotFoundException extends RuntimeException {

	public StockNotFoundException(String ticker) {
		super("No stock found for ticker '" + ticker + "'");
	}
}
