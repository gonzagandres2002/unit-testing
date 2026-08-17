package com.stocklens.provider.finnhub;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocklens.config.StockLensProperties;
import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataException;
import com.stocklens.provider.FinancialDataProvider;
import com.stocklens.provider.RateLimitedException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link FinancialDataProvider} backed by the Finnhub REST API
 * (https://finnhub.io/docs/api). Combines three free-tier endpoints:
 * {@code /stock/profile2} (name, industry, market cap), {@code /quote}
 * (current price) and {@code /stock/metric} (P/E, dividend yield).
 */
@Component
public class FinnhubStockProvider implements FinancialDataProvider {

	/**
	 * Finnhub does not formally document the metric field names, so the known
	 * candidates are tried in order and the metrics stay optional.
	 */
	private static final String[] PE_KEYS = { "peTTM", "peBasicExclExtraTTM", "peNormalizedAnnual" };
	private static final String[] DIVIDEND_YIELD_KEYS = { "currentDividendYieldTTM", "dividendYieldIndicatedAnnual" };

	private final RestClient restClient;
	private final String apiKey;
	private final Clock clock;

	public FinnhubStockProvider(RestClient finnhubRestClient, StockLensProperties properties, Clock clock) {
		this.restClient = finnhubRestClient;
		this.apiKey = properties.finnhub().apiKey();
		this.clock = clock;
	}

	@Override
	public Optional<Stock> fetchStock(String ticker) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new FinancialDataException(
					"Finnhub API key is not configured; set the FINNHUB_API_KEY environment variable");
		}
		Profile profile = get("/stock/profile2?symbol={ticker}", Profile.class, ticker);
		if (profile == null || profile.name() == null || profile.name().isBlank()) {
			// Finnhub answers unknown tickers with an empty JSON object.
			return Optional.empty();
		}
		Quote quote = get("/quote?symbol={ticker}", Quote.class, ticker);
		if (quote == null || quote.current() == null || quote.current() == 0.0) {
			// Finnhub answers unknown tickers with all-zero quotes.
			return Optional.empty();
		}
		Metrics metrics = get("/stock/metric?symbol={ticker}&metric=all", Metrics.class, ticker);
		return Optional.of(new Stock(
				ticker,
				profile.name(),
				profile.industry(),
				quote.current(),
				firstMetric(metrics, PE_KEYS),
				marketCapUsd(profile),
				firstMetric(metrics, DIVIDEND_YIELD_KEYS),
				clock.instant()));
	}

	private <T> T get(String path, Class<T> type, String ticker) {
		try {
			return restClient.get()
				.uri(path, ticker)
				.header("X-Finnhub-Token", apiKey)
				.retrieve()
				.body(type);
		}
		catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
				throw new RateLimitedException("Finnhub rate limit exceeded");
			}
			throw new FinancialDataException(
					"Finnhub returned HTTP " + e.getStatusCode().value() + " for " + ticker, e);
		}
		catch (ResourceAccessException e) {
			throw new FinancialDataException("Finnhub unreachable or timed out for " + ticker, e);
		}
		catch (RestClientException e) {
			// A read timeout can also fire while the body is being consumed; it
			// then surfaces as a generic extraction error with an IO cause
			// rather than as a ResourceAccessException.
			if (hasIoCause(e)) {
				throw new FinancialDataException("Finnhub unreachable or timed out for " + ticker, e);
			}
			throw new FinancialDataException("Finnhub returned an invalid response for " + ticker, e);
		}
	}

	private static boolean hasIoCause(Throwable throwable) {
		for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof java.io.IOException) {
				return true;
			}
		}
		return false;
	}

	private static Double marketCapUsd(Profile profile) {
		// profile2 reports market capitalization in millions of USD.
		return profile.marketCapMillions() == null ? null : profile.marketCapMillions() * 1_000_000d;
	}

	private static Double firstMetric(Metrics metrics, String... keys) {
		if (metrics == null || metrics.metric() == null) {
			return null;
		}
		for (String key : keys) {
			if (metrics.metric().get(key) instanceof Number number) {
				return number.doubleValue();
			}
		}
		return null;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Profile(
			String name,
			@JsonProperty("finnhubIndustry") String industry,
			@JsonProperty("marketCapitalization") Double marketCapMillions) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Quote(@JsonProperty("c") Double current) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Metrics(Map<String, Object> metric) {
	}
}
