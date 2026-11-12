package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JitteredLoginDelayTest {

	@Test
	void drawsDelaysWithinTheConfiguredRange() {
		JitteredLoginDelay delay = new JitteredLoginDelay();

		for (int sample = 0; sample < 10000; sample++) {
			long millis = delay.nextDelayMillis();

			assertTrue(millis >= 70L);
			assertTrue(millis <= 110L);
		}

	}
}
