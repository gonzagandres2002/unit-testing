package com.stocklens;

import java.time.Instant;
import java.util.Optional;

import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataException;
import com.stocklens.provider.FinancialDataProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests through the full HTTP → controller → service stack, with
 * only the external provider replaced by a mock. The context is rebuilt per
 * test because the service cache is stateful. These ten tests are the whole
 * integration layer: they exercise the happy-path screening and detail
 * endpoints end to end, the graceful-degradation path, and the HTTP contract
 * (validation and error mapping) that a client depends on.
 *
 * <p>Each test follows the Arrange-Act-Assert (AAA) pattern: Arrange stubs
 * the mocked {@link FinancialDataProvider} (via {@link #givenHealthyProvider()}
 * or an inline stub) where the request reaches the service — validation
 * failures are rejected before the service is ever called, so they have
 * nothing to arrange — Act performs the HTTP request(s) under test, and Assert
 * checks the response.
 */
@SpringBootTest(properties = "stocklens.screener.tickers=MSFT,GOOGL,AAPL")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StockScreenerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FinancialDataProvider provider;

	private static Stock stock(String ticker, String name, double price, Double pe, double marketCap) {
		return new Stock(ticker, name, "Technology", price, pe, marketCap, null,
				Instant.parse("2026-08-17T12:00:00Z"));
	}

	private void givenHealthyProvider() {
		when(provider.fetchStock("MSFT"))
			.thenReturn(Optional.of(stock("MSFT", "Microsoft", 512.3, 29.5, 3.1e12)));
		when(provider.fetchStock("GOOGL"))
			.thenReturn(Optional.of(stock("GOOGL", "Alphabet", 201.1, 27.2, 2.4e12)));
		when(provider.fetchStock("AAPL"))
			.thenReturn(Optional.of(stock("AAPL", "Apple", 230.5, 34.0, 3.5e12)));
	}

	@Test
	void defaultRequestSortsByMarketCapDescending() throws Exception {
		// Arrange
		givenHealthyProvider();

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks"));

		// Assert
		response.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].ticker").value("AAPL"))
			.andExpect(jsonPath("$[1].ticker").value("MSFT"))
			.andExpect(jsonPath("$[2].ticker").value("GOOGL"));
	}

	@Test
	void screensFiltersAndSortsThroughTheFullStack() throws Exception {
		// Arrange
		givenHealthyProvider();

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks")
				.param("maxPe", "30")
				.param("sortBy", "pe")
				.param("order", "asc"));

		// Assert
		response.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].ticker").value("GOOGL"))
			.andExpect(jsonPath("$[1].ticker").value("MSFT"));
	}

	@Test
	void searchAndDetailEndpointWorkTogether() throws Exception {
		// Arrange
		givenHealthyProvider();

		// Act — search endpoint
		ResultActions searchResponse = mockMvc.perform(get("/api/stocks").param("q", "apple"));

		// Assert — search endpoint
		searchResponse.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].ticker").value("AAPL"));

		// Act — detail endpoint, using the ticker the search step just returned
		ResultActions detailResponse = mockMvc.perform(get("/api/stocks/AAPL"));

		// Assert — detail endpoint
		detailResponse.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Apple"))
			.andExpect(jsonPath("$.marketCap").value(3.5e12));
	}

	@Test
	void completeProviderOutageWithColdCacheYields503() throws Exception {
		// Arrange
		when(provider.fetchStock(anyString())).thenThrow(new FinancialDataException("API down"));

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks"));

		// Assert
		response.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value(503));
	}

	@Test
	void unknownTickerYields404ProblemDetail() throws Exception {
		// Arrange — a valid symbol that is not in the configured universe
		givenHealthyProvider();

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks/ZZZZ"));

		// Assert
		response.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("No stock found for ticker 'ZZZZ'"));
	}

	@Test
	void zeroMaxPeIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks").param("maxPe", "0"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void negativeMinMarketCapIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks").param("minMarketCap", "-1"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void nonNumericFilterValueIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks").param("maxPe", "cheap"));

		// Assert
		response.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Parameter 'maxPe' has an invalid value"));
	}

	@Test
	void unknownSortFieldIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks").param("sortBy", "volume"));

		// Assert
		response.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(
					"Unknown sortBy 'volume'; expected one of: name, price, pe, marketCap"));
	}

	@Test
	void malformedTickerIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks/{ticker}", "MS FT!"))
			.andExpect(status().isBadRequest());
	}
}
