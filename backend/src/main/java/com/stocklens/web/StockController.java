package com.stocklens.web;

import java.util.List;

import com.stocklens.domain.Stock;
import com.stocklens.service.StockQuery;
import com.stocklens.service.StockService;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
@Validated
public class StockController {

	private final StockService stockService;

	public StockController(StockService stockService) {
		this.stockService = stockService;
	}

	@GetMapping
	public List<Stock> search(
			@RequestParam(name = "q", required = false) String search,
			@RequestParam(required = false) @Positive Double maxPe,
			@RequestParam(required = false) @PositiveOrZero Double minMarketCap,
			@RequestParam(defaultValue = "marketCap") String sortBy,
			@RequestParam(defaultValue = "desc") String order) {
		return stockService.search(new StockQuery(
				search,
				maxPe,
				minMarketCap,
				StockQuery.SortBy.from(sortBy),
				StockQuery.Direction.from(order)));
	}

	@GetMapping("/{ticker}")
	public Stock byTicker(@PathVariable @Pattern(regexp = "[A-Za-z][A-Za-z0-9.\\-]{0,9}") String ticker) {
		return stockService.getByTicker(ticker);
	}
}
