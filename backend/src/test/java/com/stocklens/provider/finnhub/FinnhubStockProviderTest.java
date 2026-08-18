package com.stocklens.provider.finnhub;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.stocklens.config.StockLensProperties;
import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataException;
import com.stocklens.provider.RateLimitedException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the Finnhub client against a local mock HTTP server, covering the
 * success path and every failure mode required by the spec: HTTP error,
 * timeout, rate limit, invalid payload and missing fields. No test talks to
 * the real Finnhub API.
 *
 * <p>Each test follows the Arrange-Act-Assert (AAA) pattern: {@code @BeforeEach}
 * arranges the shared fixture (the mock server and a default provider), the
 * test method arranges any response-specific stubbing, then performs the
 * single operation under test (Act) and verifies its outcome (Assert).
 */
class FinnhubStockProviderTest {

	private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

	private static final String PROFILE_JSON = """
			{"name": "Microsoft Corp", "ticker": "MSFT", "finnhubIndustry": "Technology",
			 "marketCapitalization": 3100000.0, "country": "US"}""";
	private static final String QUOTE_JSON = """
			{"c": 512.3, "d": 1.2, "dp": 0.23, "h": 515.0, "l": 508.1, "o": 509.0, "pc": 511.1}""";
	private static final String METRICS_JSON = """
			{"metric": {"peTTM": 29.5, "currentDividendYieldTTM": 0.71, "52WeekHigh": 520.0},
			 "metricType": "all", "symbol": "MSFT"}""";

	private MockWebServer server;
	private FinnhubStockProvider provider;

	@BeforeEach
	void startServer() throws Exception {
		server = new MockWebServer();
		server.start();
		provider = provider("test-key");
	}

	@AfterEach
	void stopServer() throws Exception {
		server.shutdown();
	}

	private FinnhubStockProvider provider(String apiKey) {
		String baseUrl = server.url("/").toString();
		var finnhub = new StockLensProperties.Finnhub(
				baseUrl, apiKey, Duration.ofMillis(500), Duration.ofMillis(500));
		var properties = new StockLensProperties(finnhub, null);
		var requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(finnhub.connectTimeout()).build());
		requestFactory.setReadTimeout(finnhub.readTimeout());
		RestClient restClient = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.build();
		return new FinnhubStockProvider(restClient, properties, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void enqueueJson(String body) {
		server.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/json"));
	}

	@Test
	void successfulResponseIsMappedToStock() throws Exception {
		// Arrange
		enqueueJson(PROFILE_JSON);
		enqueueJson(QUOTE_JSON);
		enqueueJson(METRICS_JSON);

		// Act
		Stock stock = provider.fetchStock("MSFT").orElseThrow();
		RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);

		// Assert
		assertThat(stock.ticker()).isEqualTo("MSFT");
		assertThat(stock.name()).isEqualTo("Microsoft Corp");
		assertThat(stock.sector()).isEqualTo("Technology");
		assertThat(stock.price()).isEqualTo(512.3);
		assertThat(stock.peRatio()).isEqualTo(29.5);
		// profile2 reports market cap in millions of USD
		assertThat(stock.marketCap()).isEqualTo(3.1e12);
		assertThat(stock.dividendYield()).isEqualTo(0.71);
		assertThat(stock.lastUpdated()).isEqualTo(NOW);
		assertThat(first.getPath()).isEqualTo("/stock/profile2?symbol=MSFT");
		assertThat(first.getHeader("X-Finnhub-Token")).isEqualTo("test-key");
	}

	@Test
	void unknownTickerWithEmptyProfileYieldsEmptyWithoutFurtherCalls() {
		// Arrange
		enqueueJson("{}");

		// Act
		Optional<Stock> result = provider.fetchStock("ZZZZ");

		// Assert
		assertThat(result).isEmpty();
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void allZeroQuoteYieldsEmpty() {
		// Arrange
		enqueueJson(PROFILE_JSON);
		enqueueJson("{\"c\": 0, \"d\": null, \"dp\": null, \"h\": 0, \"l\": 0, \"o\": 0, \"pc\": 0}");

		// Act
		Optional<Stock> result = provider.fetchStock("DELISTED");

		// Assert
		assertThat(result).isEmpty();
	}

	@Test
	void missingMetricsStillYieldsStockWithNullRatios() {
		// Arrange
		enqueueJson(PROFILE_JSON);
		enqueueJson(QUOTE_JSON);
		enqueueJson("{}");

		// Act
		Optional<Stock> stock = provider.fetchStock("MSFT");

		// Assert
		assertThat(stock).isPresent();
		assertThat(stock.get().peRatio()).isNull();
		assertThat(stock.get().dividendYield()).isNull();
	}

	@Test
	void fallbackMetricKeysAreUsedWhenPrimaryOnesAreAbsent() {
		// Arrange
		enqueueJson(PROFILE_JSON);
		enqueueJson(QUOTE_JSON);
		enqueueJson("{\"metric\": {\"peBasicExclExtraTTM\": 28, \"dividendYieldIndicatedAnnual\": 0.8}}");

		// Act
		Stock stock = provider.fetchStock("MSFT").orElseThrow();

		// Assert
		assertThat(stock.peRatio()).isEqualTo(28.0);
		assertThat(stock.dividendYield()).isEqualTo(0.8);
	}

	@Test
	void httpErrorBecomesFinancialDataException() {
		// Arrange
		server.enqueue(new MockResponse().setResponseCode(500));

		// Act & Assert
		assertThatThrownBy(() -> provider.fetchStock("MSFT"))
			.isInstanceOf(FinancialDataException.class)
			.hasMessageContaining("HTTP 500");
	}

	@Test
	void rateLimitBecomesRateLimitedException() {
		// Arrange
		server.enqueue(new MockResponse().setResponseCode(429));

		// Act & Assert
		assertThatThrownBy(() -> provider.fetchStock("MSFT"))
			.isInstanceOf(RateLimitedException.class);
	}

	@Test
	void invalidJsonBecomesFinancialDataException() {
		// Arrange
		server.enqueue(new MockResponse().setBody("<html>maintenance</html>")
			.addHeader("Content-Type", "application/json"));

		// Act & Assert
		assertThatThrownBy(() -> provider.fetchStock("MSFT"))
			.isInstanceOf(FinancialDataException.class);
	}

	@Test
	void timeoutBecomesFinancialDataException() {
		// Arrange
		server.enqueue(new MockResponse().setBody(PROFILE_JSON)
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(2, TimeUnit.SECONDS));

		// Act & Assert
		assertThatThrownBy(() -> provider.fetchStock("MSFT"))
			.isInstanceOf(FinancialDataException.class)
			.hasMessageContaining("timed out");
	}

	@Test
	void missingApiKeyFailsFastWithoutCallingTheApi() {
		// Arrange
		FinnhubStockProvider withoutKey = provider("");

		// Act & Assert
		assertThatThrownBy(() -> withoutKey.fetchStock("MSFT"))
			.isInstanceOf(FinancialDataException.class)
			.hasMessageContaining("FINNHUB_API_KEY");
		assertThat(server.getRequestCount()).isZero();
	}
}
