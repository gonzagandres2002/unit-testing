package com.stocklens;

import java.time.Instant;
import java.util.Optional;

import com.stocklens.domain.Stock;
import com.stocklens.provider.FinancialDataProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test through the full HTTP → controller → service stack, with
 * only the external provider replaced by a mock — proves the pieces are
 * actually wired together (routing, parameter binding, JSON serialization),
 * which {@link com.stocklens.service.StockServiceTest}'s unit tests
 * structurally cannot: they construct {@code StockService} with {@code new}
 * and never touch Spring.
 *
 * <p>Follows Arrange-Act-Assert: Arrange stubs the mocked
 * {@link FinancialDataProvider}, Act performs the HTTP request under test,
 * Assert checks the response.
 */
@SpringBootTest(properties = "stocklens.screener.tickers=MSFT,GOOGL,AAPL")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StockScreenerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FinancialDataProvider provider;

	@Test
	void screensFiltersAndSortsThroughTheFullStack() throws Exception {
		// Arrange
		when(provider.fetchStock("MSFT"))
			.thenReturn(Optional.of(stock("MSFT", "Microsoft", 512.3, 29.5, 3.1e12)));
		when(provider.fetchStock("GOOGL"))
			.thenReturn(Optional.of(stock("GOOGL", "Alphabet", 201.1, 27.2, 2.4e12)));
		when(provider.fetchStock("AAPL"))
			.thenReturn(Optional.of(stock("AAPL", "Apple", 230.5, 34.0, 3.5e12)));

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks")
				.param("maxPe", "30")
				.param("sortBy", "pe")
				.param("order", "asc"));

		// Assert — only MSFT and GOOGL clear the P/E filter, cheapest first
		response.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].ticker").value("GOOGL"))
			.andExpect(jsonPath("$[1].ticker").value("MSFT"));
	}

	private static Stock stock(String ticker, String name, double price, Double pe, double marketCap) {
		return new Stock(ticker, name, "Technology", price, pe, marketCap, null,
				Instant.parse("2026-08-17T12:00:00Z"));
	}
}
