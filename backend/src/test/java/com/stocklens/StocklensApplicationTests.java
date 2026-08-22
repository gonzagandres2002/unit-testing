package com.stocklens;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the Spring context wires up cleanly — every bean is
 * constructible and every dependency resolvable. It asserts nothing on
 * purpose; a failure to start the context is the failure this test exists
 * to catch, and no unit test over an individually {@code new}-constructed
 * class can catch a missing bean or a broken binding the way this can.
 */
@SpringBootTest
class StocklensApplicationTests {

	@Test
	void contextLoads() {
	}

}
