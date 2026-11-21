package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;

class HmacActivationIdentityVerifierTest {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
	private static final Instant EXPIRES_AT = NOW.plusSeconds(240);
	private static final UUID INVITATION_ID = UUID.randomUUID();
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID CHALLENGE_ID = UUID.randomUUID();
	private static final String PHONE_NUMBER = "0600000000";
	private static final String CODE = "000042";
	private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	private final ActivationIdentityChallengeStore challengeStore = mock(ActivationIdentityChallengeStore.class);
	private final ActivationCodeDigester codeDigester = new ActivationCodeDigester(KEY);
	private HmacActivationIdentityVerifier verifier;

	@BeforeEach
	void setUp() {
		ObjectProvider<Clock> clocks = mock();
		when(clocks.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
		verifier = new HmacActivationIdentityVerifier(challengeStore, codeDigester, clocks);
	}

	@Test
	void anAbsentChallengeIsRejectedWithoutCountingAnAttempt() {
		when(challengeStore.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(Optional.empty());

		assertRejectedWithoutChangingChallenge(PHONE_NUMBER);
	}

	@Test
	void aDifferentPhoneNumberIsRejectedEvenWithTheCorrectCode() {
		givenChallenge(EXPIRES_AT, 0, null, null);

		assertRejectedWithoutChangingChallenge("0600000001");
	}

	@ParameterizedTest
	@ValueSource(longs = { -1, 0 })
	void aChallengeIsRejectedAtAndAfterItsExpiry(long secondsUntilExpiry) {
		givenChallenge(NOW.plusSeconds(secondsUntilExpiry), 0, null, null);

		assertRejectedWithoutChangingChallenge(PHONE_NUMBER);
	}

	@Test
	void aRevokedChallengeIsRejectedEvenWithTheCorrectCode() {
		givenChallenge(EXPIRES_AT, 0, null, NOW.minusSeconds(1));

		assertRejectedWithoutChangingChallenge(PHONE_NUMBER);
	}

	@Test
	void aConsumedChallengeIsRejectedEvenWithTheCorrectCode() {
		givenChallenge(EXPIRES_AT, 0, NOW.minusSeconds(1), null);

		assertRejectedWithoutChangingChallenge(PHONE_NUMBER);
	}

	@Test
	void aChallengeAtTheFailedAttemptLimitIsRejectedEvenWithTheCorrectCode() {
		givenChallenge(EXPIRES_AT, 3, null, null);

		assertRejectedWithoutChangingChallenge(PHONE_NUMBER);
	}

	@Test
	void aCorrectCodeReturnsTheChallengeIdWithoutConsumingIt() {
		givenChallenge(EXPIRES_AT, 2, null, null);

		assertEquals(Optional.of(CHALLENGE_ID), verifier.verify(INVITATION_ID, USER_ID, PHONE_NUMBER, CODE));

		verify(challengeStore).findCurrentByInvitationAndUser(INVITATION_ID, USER_ID);
		verifyNoMoreInteractions(challengeStore);
	}

	@Test
	void anIncorrectCodeRecordsOneFailedAttemptForTheCurrentChallenge() {
		givenChallenge(EXPIRES_AT, 0, null, null);

		assertTrue(verifier.verify(INVITATION_ID, USER_ID, PHONE_NUMBER, "000043").isEmpty());

		verify(challengeStore).findCurrentByInvitationAndUser(INVITATION_ID, USER_ID);
		verify(challengeStore).recordFailedAttempt(CHALLENGE_ID, NOW);
		verifyNoMoreInteractions(challengeStore);
	}

	private void givenChallenge(Instant expiresAt, int failedAttempts, Instant consumedAt, Instant revokedAt) {
		ActivationIdentityChallenge challenge = new ActivationIdentityChallenge(CHALLENGE_ID, USER_ID, PHONE_NUMBER,
				INVITATION_ID, codeDigester.digest(CHALLENGE_ID, CODE), failedAttempts, NOW.minusSeconds(60), expiresAt,
				consumedAt, revokedAt, NOW.minusSeconds(60), NOW.plusSeconds(60));
		when(challengeStore.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(Optional.of(challenge));
	}

	@Test
	void refusesACorrectCodeUntilTheSendIsConfirmed() {
		ActivationIdentityChallenge challenge = new ActivationIdentityChallenge(CHALLENGE_ID, USER_ID, PHONE_NUMBER,
				INVITATION_ID, codeDigester.digest(CHALLENGE_ID, CODE), 0, NOW, NOW.plusSeconds(300), null,
				null, null, NOW.plusSeconds(120));
		when(challengeStore.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(Optional.of(challenge));
		assertTrue(verifier.verify(INVITATION_ID, USER_ID, PHONE_NUMBER, CODE).isEmpty());
		verify(challengeStore, org.mockito.Mockito.never()).recordFailedAttempt(any(), any());
	}

	private void assertRejectedWithoutChangingChallenge(String phoneNumber) {
		assertTrue(verifier.verify(INVITATION_ID, USER_ID, phoneNumber, CODE).isEmpty());

		verify(challengeStore).findCurrentByInvitationAndUser(INVITATION_ID, USER_ID);
		verifyNoMoreInteractions(challengeStore);
	}
}
