package fr.gestionplus.server.auth;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class JitteredLoginDelay implements LoginDelay {

	private static final long MIN_DELAY_MS = 70L;
	private static final long MAX_DELAY_MS = 110L;
	
	private final SecureRandom random = new SecureRandom();

	@Override
	public void pause() {

		try {
			Thread.sleep(nextDelayMillis());
		} catch (InterruptedException interruption) {
			Thread.currentThread().interrupt();
		}

	}

	long nextDelayMillis() {
		return random.nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1L);
	}
}
