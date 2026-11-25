package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import fr.gestionplus.server.auth.AccountMfaService.MfaStatus;
import fr.gestionplus.server.auth.AccountMfaStore.Factor;

class AccountMfaStatusTest {

	private final UUID userID = UUID.randomUUID();
	private final MfaSecretCipher cipher = new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
	private final AccountMfaStore store = mock(AccountMfaStore.class);

	@Test
	void readsTheFactorAndTheRecoveryMarkerWithoutConsumingATotp() {
		var service = service(cipher);
		when(store.findFactor(userID)).thenReturn(Optional.empty());
		assertEquals(MfaStatus.NOT_CONFIGURED, service.getMfaStatus(userID));

		byte[] encrypted = cipher.encrypt(userID, null, new byte[MfaSecretCipher.SECRET_BYTES]);
		when(store.findFactor(userID)).thenReturn(Optional.of(new Factor(encrypted, 12)));
		assertEquals(MfaStatus.CONFIGURED, service.getMfaStatus(userID));

		when(store.findFactor(userID)).thenReturn(Optional.of(new Factor(null, -1)));
		assertEquals(MfaStatus.RECOVERY_REQUIRED, service.getMfaStatus(userID));
		verify(store, times(3)).findFactor(userID);
		verifyNoMoreInteractions(store);
	}

	@Test
	void cannotMistakeMissingCryptoUnreadableCiphertextOrAStorageFailureForAnUnconfiguredAccount() {
		when(store.findFactor(userID)).thenReturn(Optional.empty());
		assertEquals(MfaStatus.UNAVAILABLE, service(new MfaSecretCipher("")).getMfaStatus(userID));

		var service = service(cipher);
		when(store.findFactor(userID)).thenReturn(Optional.of(new Factor(new byte[] { 1 }, -1)));
		assertEquals(MfaStatus.UNAVAILABLE, service.getMfaStatus(userID));

		when(store.findFactor(userID)).thenThrow(new DataAccessResourceFailureException("indisponibilité fictive"));
		assertEquals(MfaStatus.UNAVAILABLE, service.getMfaStatus(userID));
	}

	private AccountMfaService service(MfaSecretCipher secretCipher) {
		return new AccountMfaService(null, store, null, null, null, null, null, secretCipher, null, null,
				null, null, null, null, mock(PlatformTransactionManager.class), null, Clock.systemUTC());
	}
}
