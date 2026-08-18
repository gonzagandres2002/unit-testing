package com.stocklens.web;

import java.time.Instant;
import java.util.List;

import com.stocklens.domain.Stock;
import com.stocklens.service.DataUnavailableException;
import com.stocklens.service.StockNotFoundException;
import com.stocklens.service.StockQuery;
import com.stocklens.service.StockService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests: HTTP contract, parameter validation and error mapping.
 *
 * <p>Each test follows the Arrange-Act-Assert (AAA) pattern. Arrange stubs
 * the mocked {@link StockService}; Act performs the single HTTP request
 * under test ({@code mockMvc.perform(...)}); Assert checks the response
 * ({@code andExpect(...)}) and, where relevant, the query object the
 * controller actually passed to the service. Tests that only exercise
 * request validation — which is rejected before the service is ever called
 * — have nothing to arrange.
 */
@WebMvcTest(StockController.class)
class StockControllerTest {

	private static final Stock MSFT = new Stock("MSFT", "Microsoft", "Technology",
			512.3, 29.5, 3.1e12, 0.71, Instant.parse("2026-08-17T12:00:00Z"));

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StockService stockService;

	@Test
	void listReturnsJsonArrayOfStocks() throws Exception {
		// Arrange
		when(stockService.search(any())).thenReturn(List.of(MSFT));

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks"));

		// Assert
		response.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].ticker").value("MSFT"))
			.andExpect(jsonPath("$[0].name").value("Microsoft"))
			.andExpect(jsonPath("$[0].price").value(512.3))
			.andExpect(jsonPath("$[0].peRatio").value(29.5))
			.andExpect(jsonPath("$[0].marketCap").value(3.1e12));
	}

	@Test
	void defaultQuerySortsByMarketCapDescending() throws Exception {
		// Arrange
		when(stockService.search(any())).thenReturn(List.of());

		// Act
		mockMvc.perform(get("/api/stocks")).andExpect(status().isOk());
		ArgumentCaptor<StockQuery> captor = ArgumentCaptor.forClass(StockQuery.class);
		verify(stockService).search(captor.capture());
		StockQuery query = captor.getValue();

		// Assert
		assertThat(query.search()).isNull();
		assertThat(query.maxPe()).isNull();
		assertThat(query.minMarketCapBillions()).isNull();
		assertThat(query.sortBy()).isEqualTo(StockQuery.SortBy.MARKET_CAP);
		assertThat(query.direction()).isEqualTo(StockQuery.Direction.DESC);
	}

	@Test
	void allQueryParametersArePassedToTheService() throws Exception {
		// Arrange
		when(stockService.search(any())).thenReturn(List.of());

		// Act
		mockMvc.perform(get("/api/stocks")
				.param("q", "micro")
				.param("maxPe", "30")
				.param("minMarketCap", "10")
				.param("sortBy", "pe")
				.param("order", "asc"))
			.andExpect(status().isOk());
		ArgumentCaptor<StockQuery> captor = ArgumentCaptor.forClass(StockQuery.class);
		verify(stockService).search(captor.capture());
		StockQuery query = captor.getValue();

		// Assert
		assertThat(query.search()).isEqualTo("micro");
		assertThat(query.maxPe()).isEqualTo(30.0);
		assertThat(query.minMarketCapBillions()).isEqualTo(10.0);
		assertThat(query.sortBy()).isEqualTo(StockQuery.SortBy.PE);
		assertThat(query.direction()).isEqualTo(StockQuery.Direction.ASC);
	}

	@Test
	void zeroMaxPeIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks").param("maxPe", "0"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void negativeMaxPeIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks").param("maxPe", "-5"))
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
	void unknownOrderIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks").param("order", "sideways"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void singleStockIsReturnedByTicker() throws Exception {
		// Arrange
		when(stockService.getByTicker("MSFT")).thenReturn(MSFT);

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks/MSFT"));

		// Assert
		response.andExpect(status().isOk())
			.andExpect(jsonPath("$.ticker").value("MSFT"));
	}

	@Test
	void unknownTickerYields404ProblemDetail() throws Exception {
		// Arrange
		when(stockService.getByTicker("ZZZZ")).thenThrow(new StockNotFoundException("ZZZZ"));

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks/ZZZZ"));

		// Assert
		response.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("No stock found for ticker 'ZZZZ'"));
	}

	@Test
	void malformedTickerIsRejected() throws Exception {
		// Arrange — no stubbing needed; validation happens before the service is called

		// Act & Assert
		mockMvc.perform(get("/api/stocks/{ticker}", "MS FT!"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void unavailableDataYields503ProblemDetail() throws Exception {
		// Arrange
		when(stockService.search(any()))
			.thenThrow(new DataUnavailableException("Stock data is currently unavailable"));

		// Act
		ResultActions response = mockMvc.perform(get("/api/stocks"));

		// Assert
		response.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value("Stock data is currently unavailable"));
	}
}
