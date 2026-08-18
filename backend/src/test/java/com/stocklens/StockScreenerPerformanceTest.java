package com.stocklens;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Performance test for the full HTTP stack: {@code MockMvc} → {@link
 * com.stocklens.web.StockController} → {@link com.stocklens.service.StockService}
 * → a mocked {@link FinancialDataProvider} (standing in for
 * {@code FinnhubStockProvider}, exactly like {@link StockScreenerIntegrationTest}
 * — no test in this project ever calls the real Finnhub API).
 *
 * <p>Unlike {@link com.stocklens.service.StockServicePerformanceTest}, which
 * measures the screener logic in isolation, this test also exercises request
 * routing, parameter validation and JSON serialization — the parts of the
 * request that only run inside a real Spring context. The universe size is
 * set via {@link DynamicPropertySource} because {@code @SpringBootTest}
 * properties must be compile-time constants and can't hold a generated list
 * of 1,000 tickers.
 *
 * <p>Each test follows Arrange-Act-Assert: {@code @BeforeEach} arranges the
 * mocked provider to answer for any of the 1,000 configured tickers, Act
 * performs the HTTP request under a time budget, and Assert checks the
 * response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StockScreenerPerformanceTest {

	private static final int LARGE_UNIVERSE_SIZE = 1_000;
	private static final Duration REQUEST_BUDGET = Duration.ofSeconds(2);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FinancialDataProvider provider;

	@DynamicPropertySource
	static void largeTickerUniverse(DynamicPropertyRegistry registry) {
		List<String> tickers = new ArrayList<>(LARGE_UNIVERSE_SIZE);
		for (int i = 0; i < LARGE_UNIVERSE_SIZE; i++) {
			tickers.add("TCK" + i);
		}
		registry.add("stocklens.screener.tickers", () -> String.join(",", tickers));
	}

	@BeforeEach
	void arrangeMockedProviderForTheWholeUniverse() {
		// Arrange: a deterministic fake response per ticker, computed on the
		// fly instead of stubbed individually — 1,000 explicit when(...)
		// calls would themselves be slow to set up and would skew the timing
		// this test is meant to measure.
		Random random = new Random(42);
		when(provider.fetchStock(anyString())).thenAnswer(invocation -> {
			String ticker = invocation.getArgument(0);
			int index = Integer.parseInt(ticker.substring(3));
			double price = 1 + random.nextDouble() * 999;
			Double pe = index % 20 == 0 ? null : 1 + random.nextDouble() * 99;
			double marketCap = 1e8 + random.nextDouble() * 1e12;
			return java.util.Optional.of(new Stock(ticker, "Company " + index, "Sector " + (index % 10),
					price, pe, marketCap, 0.01, Instant.EPOCH));
		});
	}

	@Test
	void unfilteredRequestOverLargeUniverseCompletesWithinBudget() throws Exception {
		// Act & Assert
		ResultActions response = assertTimeout(REQUEST_BUDGET, () -> mockMvc.perform(get("/api/stocks")));

		response.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(LARGE_UNIVERSE_SIZE));
	}

	@Test
	void filteredSortedRequestOverLargeUniverseCompletesWithinBudget() throws Exception {
		// Act & Assert
		ResultActions response = assertTimeout(REQUEST_BUDGET, () -> mockMvc.perform(get("/api/stocks")
				.param("q", "Company 1")
				.param("maxPe", "50")
				.param("minMarketCap", "1")
				.param("sortBy", "pe")
				.param("order", "asc")));

		response.andExpect(status().isOk());
	}
}
