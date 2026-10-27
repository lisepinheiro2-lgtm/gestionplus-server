package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

public class CredentialServiceTest {

	private PasswordEncoder encoder;
	private CredentialService service;

	@BeforeEach
	void setUp() {
		encoder = new PasswordSecurityConfig().passwordEncoder();
		service = new CredentialService(encoder, new PasswordPolicy(rawPassword -> false));
	}

	@Test
	void rejectsNullPassword() {
		assertThrows(IllegalArgumentException.class, () -> service.createCredential(UUID.randomUUID(), null));
	}

	@Test
	void rejectsBlankPassword() {
		assertThrows(IllegalArgumentException.class, () -> service.createCredential(UUID.randomUUID(), "  "));
	}

	@Test
	void createsHashedCredential() {
		String password = "jzolm57GhjABC";
		UserCredential credential = service.createCredential(UUID.randomUUID(), password);

		assertTrue(encoder.matches(password, credential.getPasswordHash()));
		assertNotEquals(password, credential.getPasswordHash());
	}

	@Test
	void acceptsCorrectPassword() {
		String password = "jzolm57GhjABC ";
		UserCredential credential = service.createCredential(UUID.randomUUID(), password);

		assertTrue(service.verifyPassword(credential, password));
	}

	@Test
	void rejectsIncorrectPassword() {
		String password = "jzolm57GhjABC";
		UserCredential credential = service.createCredential(UUID.randomUUID(), password);

		assertFalse(service.verifyPassword(credential, "jzolm57 GhjABC "));
	}

	@Test
	void rejectsNullPasswordVerify() {
		UserCredential credential = service.createCredential(UUID.randomUUID(), "ejelr-test-password");

		assertFalse(service.verifyPassword(credential, null));
	}

	@Test
	void rejectsBlankPasswordVerify() {
		UserCredential credential = service.createCredential(UUID.randomUUID(), "ejelr-test-password");

		assertFalse(service.verifyPassword(credential, "  "));
	}

	@Test
	void preservesLeadingAndTrailingSpaces() {
		String password = " MonMot DePasse ";
		UserCredential credential = service.createCredential(UUID.randomUUID(), password);

		assertTrue(service.verifyPassword(credential, password));
		assertFalse(service.verifyPassword(credential, "MonMot DePasse"));
	}

}
