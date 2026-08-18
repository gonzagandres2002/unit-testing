package com.stocklens.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the generated API documentation, and exports it to {@code build/}
 * so {@code ./gradlew exportOpenApiSpec} can refresh {@code docs/openapi.yaml}.
 *
 * <p>Documentation that is generated can still be generated <em>wrongly</em>.
 * These assertions pin the parts a client depends on: that both endpoints are
 * present, that the error responses are documented, and that the descriptions
 * are actually populated from Javadoc rather than silently coming out empty
 * when the therapi annotation processor is dropped from the build.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

	private static final String SCREEN = "$.paths['/api/stocks'].get";
	private static final String DETAIL = "$.paths['/api/stocks/{ticker}'].get";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void specIsServedAndDescribesBothEndpoints() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.info.title").value("StockLens API"))
			.andExpect(jsonPath(SCREEN + ".summary").value("Screen the universe"))
			.andExpect(jsonPath(SCREEN + ".tags[0]").value("stocks"))
			.andExpect(jsonPath(DETAIL + ".summary").value("Look up one company"))
			.andExpect(jsonPath(DETAIL + ".tags[0]").value("stocks"));
	}

	@Test
	void errorResponsesAreDocumentedOnEveryOperation() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath(SCREEN + ".responses.200").exists())
			.andExpect(jsonPath(SCREEN + ".responses.400").exists())
			.andExpect(jsonPath(SCREEN + ".responses.503").exists())
			// The screener returns [] rather than 404, so 404 must NOT be listed.
			.andExpect(jsonPath(SCREEN + ".responses.404").doesNotExist())
			.andExpect(jsonPath(DETAIL + ".responses.404").exists())
			.andExpect(jsonPath("$.components.schemas.ProblemDetail").exists())
			.andExpect(jsonPath(DETAIL + ".responses.404.content['application/problem+json']"
					+ ".schema.$ref").value("#/components/schemas/ProblemDetail"));
	}

	@Test
	void descriptionsComeFromJavadoc() throws Exception {
		String spec = mockMvc.perform(get("/v3/api-docs"))
			.andReturn().getResponse().getContentAsString();

		// Wording from the @param tags on StockController.search and from the
		// record components of Stock. If the therapi annotation processor is
		// removed from build.gradle these silently become empty.
		assertThat(spec)
			.as("query parameter descriptions are read from Javadoc")
			.contains("case-insensitive substring matched");
		assertThat(spec)
			.as("schema property descriptions are read from Javadoc")
			.contains("plain USD");
	}

	@Test
	void swaggerUiIsReachable() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void exportSpecForDocs() throws Exception {
		String json = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		// The YAML endpoint answers without a charset, so the servlet default
		// (ISO-8859-1) would mangle every em-dash. Decode explicitly.
		String yaml = mockMvc.perform(get("/v3/api-docs.yaml"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		Path out = Path.of("build", "openapi");
		Files.createDirectories(out);
		Files.writeString(out.resolve("openapi.json"), json);
		Files.writeString(out.resolve("openapi.yaml"), yaml);

		assertThat(yaml).contains("openapi:").contains("/api/stocks");
		assertThat(yaml).as("UTF-8 survived the round trip").doesNotContain("\u00c3");
	}
}
