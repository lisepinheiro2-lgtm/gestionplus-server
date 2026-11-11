package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InvitationTokenIssuerTest {

	@Test
	void reconstructsTheSameTokenAcrossRestartsWithoutStoringItInCleartext() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		UUID requestID = UUID.randomUUID();
		UUID invitationID = UUID.randomUUID();
		String token = new InvitationTokenIssuer(key).token(requestID, invitationID);

		assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
		assertEquals(token, new InvitationTokenIssuer(key).token(requestID, invitationID));
		assertNotEquals(token, InvitationTokenIssuer.digest(token));
		assertNotEquals(token, new InvitationTokenIssuer(key).token(UUID.randomUUID(), invitationID));
		assertNotEquals(token, new InvitationTokenIssuer(key).token(requestID, UUID.randomUUID()));
	}

	@Test
	void rejectsMissingOrInvalidDeliveryKeys() {
		assertThrows(IllegalStateException.class,
				() -> new InvitationTokenIssuer("").token(UUID.randomUUID(), UUID.randomUUID()));
		assertThrows(IllegalArgumentException.class, () -> new InvitationTokenIssuer("invalid"));
		assertThrows(IllegalArgumentException.class,
				() -> new InvitationTokenIssuer(Base64.getEncoder().encodeToString(new byte[16])));
	}
}
