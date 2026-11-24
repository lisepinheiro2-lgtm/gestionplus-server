package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;

class StoredPrivilegedConfirmationVerifierTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
	private static final String SESSION = "9".repeat(64);
	private static final String PREVIEW = "a".repeat(64);
	private static final String TOKEN = "fictitious-privileged-proof-" + "x".repeat(15);
	private final PrivilegedConfirmationProofStore proofStore = mock(PrivilegedConfirmationProofStore.class);
	private final StoredPrivilegedConfirmationVerifier verifier = new StoredPrivilegedConfirmationVerifier(proofStore,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void suppliesOnlyTheHashAndTheExactBindingToTheTransactionalStore() {
		String hash = PrivilegedConfirmationService.digest(TOKEN);
		when(proofStore.consumeIfValid(USER_ID, SESSION, 7, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, hash, NOW))
				.thenReturn(true);

		assertTrue(verifier.consumeRecentProof(USER_ID, SESSION, 7, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, TOKEN));

		verify(proofStore).consumeIfValid(USER_ID, SESSION, 7, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, hash, NOW);
	}

	@Test
	void preservesAStoreRefusalInsteadOfAcceptingAMereTokenFormat() {
		assertFalse(verifier.consumeRecentProof(USER_ID, SESSION, 7, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, TOKEN));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " ", "malformed", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"ééééééééééééééééééééééééééééééééééééééééééé" })
	void refusesMalformedTokensAndAlreadyHashedValuesWithoutCallingTheStore(String token) {
		assertFalse(verifier.consumeRecentProof(USER_ID, SESSION, 7, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, token));
		verifyNoInteractions(proofStore);
	}
}
