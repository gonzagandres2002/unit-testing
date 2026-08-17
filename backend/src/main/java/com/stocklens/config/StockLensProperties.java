package com.stocklens.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stocklens")
public record StockLensProperties(Finnhub finnhub, Screener screener) {

	public record Finnhub(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
	}

	public record Screener(List<String> tickers, Duration cacheTtl) {
	}
}
