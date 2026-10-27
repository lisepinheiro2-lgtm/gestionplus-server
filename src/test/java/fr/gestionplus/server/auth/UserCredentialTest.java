package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public class UserCredentialTest {

	@Test
	void rejectsNullUserId() {
		assertThrows(NullPointerException.class, () -> new UserCredential(null, "empreinteFictive"));
	}

	@Test
	void rejectsNullPasswordHash() {
		assertThrows(NullPointerException.class, () -> new UserCredential(UUID.randomUUID(), null));
	}

	@Test
	void rejectsBlankPasswordHash() {
		assertThrows(IllegalArgumentException.class, () -> new UserCredential(UUID.randomUUID(), "  "));
	}
}
