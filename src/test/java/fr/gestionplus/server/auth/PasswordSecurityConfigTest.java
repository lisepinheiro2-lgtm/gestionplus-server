package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

public class PasswordSecurityConfigTest {

	@Test
	void passwordEncodingAndVerification() {
		PasswordEncoder encoder = new PasswordSecurityConfig().passwordEncoder();
		String password = "MonMotDePasse123!";
		String hash = encoder.encode(password);
		String hash1 = encoder.encode(password);

		assertNotEquals(password, hash);
		assertNotEquals(hash, hash1);
		assertTrue(encoder.matches(password, hash));
		assertTrue(encoder.matches(password, hash1));
		assertFalse(encoder.matches("mauvaisMotDePasse", hash));
	}
}
