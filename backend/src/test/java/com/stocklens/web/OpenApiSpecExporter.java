package com.stocklens.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Build utility, not part of the test suite: it asks the running context for
 * the generated OpenAPI spec and writes it to {@code build/openapi/} so the
 * {@code exportOpenApiSpec} Gradle task can refresh {@code docs/openapi.yaml}.
 *
 * <p>Tagged {@code docgen} and therefore excluded from {@code ./gradlew test};
 * it runs only through {@code ./gradlew generateOpenApiSpec} (which
 * {@code exportOpenApiSpec} depends on). It asserts nothing about the API
 * contract — the behavior of the spec is not under test here, it is merely
 * exported.
 */
@Tag("docgen")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecExporter {

	@Autowired
	private MockMvc mockMvc;

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
		assertThat(yaml).as("UTF-8 survived the round trip").doesNotContain("Ã");
	}
}
