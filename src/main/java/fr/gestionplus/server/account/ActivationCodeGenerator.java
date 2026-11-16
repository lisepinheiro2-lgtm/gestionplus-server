package fr.gestionplus.server.account;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class ActivationCodeGenerator {

	private final SecureRandom random = new SecureRandom();

	public String generate() {
		return String.format("%06d", random.nextInt(1_000_000));
	}
}
