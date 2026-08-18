package com.stocklens.config;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import org.springdoc.core.customizers.OpenApiCustomizer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for the spec served at {@code /v3/api-docs} and rendered
 * by Swagger UI at {@code /swagger-ui.html}.
 *
 * <p>Operation and model descriptions are <em>not</em> configured here. They
 * are read from the Javadoc on {@code StockController} and {@link
 * com.stocklens.domain.Stock} at runtime, via therapi, so there is one place
 * to update a description rather than a comment and an annotation that drift
 * apart.
 */
@Configuration
public class OpenApiConfig {

	private static final String PROBLEM_JSON = "application/problem+json";
	private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

	/** Top-level metadata: title, description, licence and servers. */
	@Bean
	OpenAPI stockLensOpenApi(@Value("${spring.application.name:stocklens}") String appName) {
		return new OpenAPI()
			.info(new Info().title("StockLens API")
				.version("0.0.1-SNAPSHOT")
				.description("""
						Screening data for a fixed universe of U.S. large-cap companies.

						Read-only: every endpoint is a `GET`, there is no authentication, \
						and no request changes state. Data is served from a server-side \
						snapshot refreshed from Finnhub every 10 minutes — use \
						`lastUpdated` to judge freshness, because the API keeps answering \
						`200` with stale data during a provider outage.

						**Every metric field is nullable.** The provider does not guarantee \
						each metric for each company: a firm without positive earnings has \
						no P/E, and one that pays no dividend has no yield.
						""")
				.license(new License().name("Course project — StockLens displays data only "
						+ "and makes no investment recommendations."))
				.contact(new Contact().name(appName)))
			.servers(List.of(new Server().url("/").description("This server")))
			.tags(List.of(new Tag().name("stocks").description("Screening and lookup")));
	}

	/**
	 * Adds the error responses produced by
	 * {@link com.stocklens.web.GlobalExceptionHandler} to every operation.
	 *
	 * <p>Applied globally rather than as {@code @ApiResponse} annotations on
	 * each handler method, because that is how the errors themselves work: the
	 * controller declares none of them, and the advice maps them for all
	 * endpoints at once. Annotating each method would restate the same four
	 * responses per endpoint and drift the first time one changes.
	 */
	@Bean
	OpenApiCustomizer errorResponses() {
		return openApi -> {
			Components components = openApi.getComponents();
			components.addSchemas("ProblemDetail", problemDetailSchema());

			openApi.getPaths().forEach((path, pathItem) -> {
				boolean hasPathVariable = path.contains("{");
				for (Operation operation : pathItem.readOperations()) {
					addResponse(operation, "400", "Invalid parameter — unknown `sortBy`/`order`, "
							+ "a non-numeric filter value, `maxPe <= 0`, `minMarketCap < 0`, "
							+ "or a malformed ticker.");
					if (hasPathVariable) {
						addResponse(operation, "404", "No screened company has that ticker. "
								+ "Scoped to the configured universe, not to all of Finnhub.");
					}
					addResponse(operation, "503", """
							No cached snapshot exists **and** the provider cannot supply one.

							Rarer than it looks: a single failing ticker is skipped, and a \
							failed refresh with a warm cache is served stale with a `200`. \
							A `503` means the backend has never successfully fetched data, \
							or restarted before it could. Transient — retry.""");
				}
			});
		};
	}

	private static void addResponse(Operation operation, String status, String description) {
		operation.getResponses().addApiResponse(status, new ApiResponse()
			.description(description)
			.content(new Content().addMediaType(PROBLEM_JSON,
					new MediaType().schema(new Schema<>().$ref(PROBLEM_REF)))));
	}

	/** RFC 9457 problem detail, the shape of every error body. */
	private static Schema<?> problemDetailSchema() {
		Schema<?> schema = typed("object").description("RFC 9457 problem detail.");
		schema.setProperties(Map.of(
				"type", typed("string").format("uri").example("about:blank"),
				"title", typed("string").example("Bad Request"),
				"status", typed("integer").format("int32").example(400),
				"detail", typed("string")
					.example("Unknown sortBy 'volume'; expected one of: name, price, pe, marketCap"),
				"instance", typed("string").format("uri")));
		return schema;
	}

	/**
	 * Builds a schema of the given JSON type.
	 *
	 * <p>OpenAPI 3.1 serializes the type from the {@code types} set, which only
	 * {@code addType} populates — the older {@code type} setter is silently
	 * dropped. {@code addType} returns a boolean rather than the schema, so it
	 * cannot be chained; hence this helper.
	 */
	private static Schema<?> typed(String type) {
		Schema<?> schema = new Schema<>();
		schema.addType(type);
		return schema;
	}
}
