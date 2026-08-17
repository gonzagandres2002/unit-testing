package com.stocklens.service;

/** No cached snapshot exists and the external provider cannot supply one. */
public class DataUnavailableException extends RuntimeException {

	public DataUnavailableException(String message) {
		super(message);
	}
}
