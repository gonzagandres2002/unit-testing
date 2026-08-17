package com.stocklens.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

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
