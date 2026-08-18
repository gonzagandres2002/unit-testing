package com.stocklens.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Infrastructure beans: the application clock and the Finnhub HTTP client. */
@Configuration
public class AppConfig {

	/**
	 * The application's source of time.
	 *
	 * <p>Injected rather than calling {@code Instant.now()} inline so that
	 * cache-expiry logic is testable: {@code StockServiceTest} substitutes a
	 * clock it can advance by hand, turning a 10-minute TTL test into a
	 * microsecond assertion instead of a {@code Thread.sleep}.
	 */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * HTTP client for Finnhub, with both timeouts configured.
	 *
	 * <p>Timeouts are not optional here. A refresh performs up to 54 calls in
	 * series on the request thread, so an unbounded read would let one
	 * unresponsive provider stall the whole endpoint; bounded waits let the
	 * service fail fast and serve the previous snapshot instead.
	 *
	 * @param properties supplies the base URL and the connect/read timeouts
	 * @return a client bound to {@code stocklens.finnhub.base-url}
	 */
	@Bean
	RestClient finnhubRestClient(StockLensProperties properties) {
		var finnhub = properties.finnhub();
		var httpClient = java.net.http.HttpClient.newBuilder()
			.connectTimeout(finnhub.connectTimeout())
			.build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(finnhub.readTimeout());
		return RestClient.builder()
			.baseUrl(finnhub.baseUrl())
			.requestFactory(requestFactory)
			.build();
	}
}
