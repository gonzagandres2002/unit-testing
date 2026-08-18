package com.stocklens.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the {@code stocklens.*} block in
 * {@code application.yml}. Bound at startup by {@code @ConfigurationPropertiesScan}
 * on {@link com.stocklens.StocklensApplication}.
 *
 * <p>Every value below can be overridden per environment with a standard
 * Spring property, an environment variable, or a test annotation:
 * <pre>{@code
 * # application.yml
 * stocklens.screener.cache-ttl: 30m
 *
 * # environment variable (relaxed binding)
 * STOCKLENS_SCREENER_CACHETTL=30m
 *
 * # inside a test
 * @SpringBootTest(properties = "stocklens.screener.tickers=MSFT,GOOGL")
 * }</pre>
 *
 * @param finnhub  external provider connection settings
 * @param screener the stock universe and cache policy
 * @see <a href="../../../../../resources/application.yml">application.yml</a>
 */
@ConfigurationProperties(prefix = "stocklens")
public record StockLensProperties(Finnhub finnhub, Screener screener) {

	/**
	 * Connection settings for the Finnhub REST API.
	 *
	 * <p>The timeouts are deliberately short. A screener refresh issues three
	 * calls per ticker in series, so a slow provider would otherwise hold an
	 * HTTP worker thread for minutes; failing fast lets the service fall back
	 * to the previous snapshot instead.
	 *
	 * @param baseUrl        API root, {@code https://finnhub.io/api/v1} in
	 *                       production and a local mock server in tests
	 * @param apiKey         Finnhub token, supplied via the
	 *                       {@code FINNHUB_API_KEY} environment variable and
	 *                       never exposed to the frontend. Blank if unset,
	 *                       which makes every fetch fail fast with a clear
	 *                       message rather than a 401 from Finnhub
	 * @param connectTimeout how long to wait for the TCP connection; default
	 *                       {@code 3s}
	 * @param readTimeout    how long to wait for the response body; default
	 *                       {@code 5s}
	 */
	public record Finnhub(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
	}

	/**
	 * The screened universe and how long a fetched snapshot stays valid.
	 *
	 * <p>Universe size is constrained by quota, not by preference: one refresh
	 * costs {@code 3 × tickers.size()} calls, and Finnhub's free tier allows
	 * 60 per minute. The default 18 tickers therefore cost 54 calls per
	 * refresh. Raising {@code tickers} above 20 will exceed the limit and
	 * trigger {@link com.stocklens.provider.RateLimitedException}.
	 *
	 * @param tickers  exchange symbols to screen, e.g.
	 *                 {@code [AAPL, MSFT, GOOGL]}
	 * @param cacheTtl how long a snapshot is served before the next request
	 *                 triggers a refresh; default {@code 10m}, which keeps
	 *                 steady-state usage near 5.4 calls/minute
	 */
	public record Screener(List<String> tickers, Duration cacheTtl) {
	}
}
