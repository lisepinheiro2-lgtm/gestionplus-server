package fr.gestionplus.server.account;

import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.ACCOUNT_ACTIVATION;
import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.MFA_ENROLLMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.SmsIdentityChallenge.Binding;
import fr.gestionplus.server.account.SmsIdentityChallengeService.VerifiedMfaChallenge;
import fr.gestionplus.server.auth.RequestRateLimitExceededException;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;

@TestPropertySource(properties = {
		"gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"gestionplus.activation.challenge-recovery.enabled=false" })
class SmsIdentityChallengePostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
	private static final Duration WINDOW = Duration.ofMinutes(10);
	private static final Duration VALIDITY = Duration.ofMinutes(5);
	private static final Duration COOLDOWN = Duration.ofSeconds(60);
	private static final String FIRST_SESSION = "a".repeat(64);
	private static final String OTHER_SESSION = "b".repeat(64);

	@Autowired
	private SmsIdentityChallengeService smsChallenges;
	@MockitoBean
	private CurrentSessionProvider sessions;
	private UserAccount account;

	@BeforeEach
	void prepareAccount() {
		when(clock.instant()).thenReturn(NOW);
		when(sessions.getActiveSessionIDHash()).thenReturn(FIRST_SESSION);
		account = createAccount(UserAccountStatus.ACTIVE, Set.of(), null);
		jdbcTemplate.update("UPDATE user_accounts SET phone_verified_at = CURRENT_TIMESTAMP WHERE user_id = ?",
				account.getUserID());
		authenticate(account);
	}

	@Test
	void countsMfaIssuanceAcrossSessionsWithoutChangingTheActivationBudget() {
		UUID userID = account.getUserID();
		UUID invitationID = UUID.randomUUID();
		SmsIdentityChallenge activation = SmsIdentityChallenge.forActivation(UUID.randomUUID(), userID,
				account.getPhoneNumber(), invitationID, "c".repeat(64), 0, NOW, NOW.plus(VALIDITY),
				null, null, NOW, NOW.plusSeconds(120));
		challengeStore.replaceCurrent(activation, NOW);
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
				UPDATE sms_identity_challenges SET purpose = 'MFA_ENROLLMENT' WHERE challenge_id = ?
				""", activation.challengeID()));

		issue();
		SmsIdentityChallenge first = challengeStore.findCurrentMfaByUser(userID).orElseThrow();
		when(sessions.getActiveSessionIDHash()).thenReturn(OTHER_SESSION);
		assertThrows(RequestRateLimitExceededException.class, this::issue);
		assertEquals(first, challengeStore.findCurrentMfaByUser(userID).orElseThrow());

		when(clock.instant()).thenReturn(NOW.plus(WINDOW));
		issue();
		assertEquals(OTHER_SESSION, challengeStore.findCurrentMfaByUser(userID).orElseThrow()
				.binding().sessionIDHash());
		assertEquals(2, challengeStore.findRecentMfaIssuances(userID, NOW.minusSeconds(1), 10).size());
		assertEquals(activation, challengeStore.findCurrentByInvitationAndUser(invitationID, userID).orElseThrow());
		assertEquals(1, challengeStore.countByInvitationAndUser(invitationID, userID));
		verify(messageSender, times(2)).send(eq(MFA_ENROLLMENT), eq(account.getPhoneNumber()), anyString());
	}

	@Test
	void rejectedAuthorizationDoesNotReserveOrSendAChallenge() {
		assertThrows(AccessDeniedException.class, () -> smsChallenges.createMfaChallenge(VALIDITY, 1, WINDOW, COOLDOWN,
				current -> {
					throw new AccessDeniedException("Autorisation refusée.");
				}));
		assertEquals(0, challengeStore.findRecentMfaIssuances(account.getUserID(), NOW.minus(WINDOW), 10).size());
		verifyNoInteractions(messageSender);
	}

	@Test
	void serializesQuotaChecksForConcurrentRequests() throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			Callable<Boolean> request = () -> {
				start.await();
				authenticate(account);
				try {
					issue();
					return true;
				} catch (RequestRateLimitExceededException limited) {
					return false;
				} finally {
					SecurityContextHolder.clearContext();
				}
			};
			var first = executor.submit(request);
			var second = executor.submit(request);
			start.countDown();

			assertFalse(first.get(10, TimeUnit.SECONDS).equals(second.get(10, TimeUnit.SECONDS)));
		}
		assertEquals(1, challengeStore.findRecentMfaIssuances(account.getUserID(), NOW.minus(WINDOW), 10).size());
		verify(messageSender).send(eq(MFA_ENROLLMENT), eq(account.getPhoneNumber()), anyString());
	}

	@Test
	void onlyTheBoundSessionAndPurposeCanConsumeTheProofOnce() {
		issue();
		String code = sentCode();
		SmsIdentityChallenge mfa = challengeStore.findCurrentMfaByUser(account.getUserID()).orElseThrow();
		var transaction = new TransactionTemplate(transactionManager);
		assertEquals(0, challengeStore.recordFailedAttempt(ACCOUNT_ACTIVATION, mfa.challengeID(), NOW));
		assertFalse(challengeStore.revokeCurrent(ACCOUNT_ACTIVATION, mfa.challengeID(), NOW));
		assertEquals(Boolean.FALSE, transaction.execute(status ->
				challengeStore.consumeIfCurrent(ACCOUNT_ACTIVATION, mfa.challengeID(), NOW)));

		VerifiedMfaChallenge verified = smsChallenges.verifyMfaChallenge(code).orElseThrow();
		when(sessions.getActiveSessionIDHash()).thenReturn(OTHER_SESSION);
		assertTrue(smsChallenges.verifyMfaChallenge(code).isEmpty());
		assertEquals(Boolean.FALSE, transaction.execute(status -> smsChallenges.consumeMfaChallenge(verified).isPresent()));
		assertEquals(0, challengeStore.findCurrentMfaByUser(account.getUserID()).orElseThrow().failedAttempts());

		when(sessions.getActiveSessionIDHash()).thenReturn(FIRST_SESSION);
		transaction.executeWithoutResult(status -> {
			assertTrue(smsChallenges.consumeMfaChallenge(verified).isPresent());
			status.setRollbackOnly();
		});
		assertEquals(Boolean.TRUE, transaction.execute(status -> smsChallenges.consumeMfaChallenge(verified).isPresent()));
		assertEquals(Boolean.FALSE, transaction.execute(status -> smsChallenges.consumeMfaChallenge(verified).isPresent()));
	}

	@Test
	void rejectsProofAfterSecurityVersionChanges() {
		issue();
		String code = sentCode();
		VerifiedMfaChallenge verified = smsChallenges.verifyMfaChallenge(code).orElseThrow();
		var transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> accountStore.advanceSessionVersion(account.getUserID()));
		UserAccount current = account(account.getUserID());

		assertThrows(AccessDeniedException.class, () -> smsChallenges.verifyMfaChallenge(code));
		authenticate(current);
		assertTrue(smsChallenges.verifyMfaChallenge(code).isEmpty());
		assertEquals(Boolean.FALSE, transaction.execute(status -> smsChallenges.consumeMfaChallenge(verified).isPresent()));
	}

	@Test
	void rejectsProofWhenTheStoredPhoneChanges() {
		issue();
		String code = sentCode();
		VerifiedMfaChallenge verified = smsChallenges.verifyMfaChallenge(code).orElseThrow();
		jdbcTemplate.update("UPDATE user_accounts SET user_phone_number = ? WHERE user_id = ?",
				"+12025550101", account.getUserID());

		assertThrows(AccessDeniedException.class, () -> smsChallenges.verifyMfaChallenge(code));
		authenticate(account(account.getUserID()));
		assertTrue(smsChallenges.verifyMfaChallenge(code).isEmpty());
		var transaction = new TransactionTemplate(transactionManager);
		assertEquals(Boolean.FALSE, transaction.execute(status -> smsChallenges.consumeMfaChallenge(verified).isPresent()));
	}

	@Test
	void wrongCodesRevokeTheCurrentChallengeWithoutResettingTheQuota() {
		issue();
		String actualCode = sentCode();
		String incorrectCode = actualCode.equals("000000") ? "000001" : "000000";
		for (int attempt = 0; attempt < SmsIdentityChallenge.MAX_FAILED_ATTEMPTS; attempt++) {
			assertTrue(smsChallenges.verifyMfaChallenge(incorrectCode).isEmpty());
		}

		assertTrue(challengeStore.findCurrentMfaByUser(account.getUserID()).isEmpty());
		assertTrue(smsChallenges.verifyMfaChallenge(actualCode).isEmpty());
		assertThrows(RequestRateLimitExceededException.class, this::issue);
	}

	@Test
	void recoversUnconfirmedMfaSendsAndKeepsThemInTheQuotaHistory() {
		Binding binding = Binding.forMfaEnrollment(account.getUserID(), account.getPhoneNumber(), FIRST_SESSION,
				account.getSessionVersion());
		SmsIdentityChallenge mfa = new SmsIdentityChallenge(UUID.randomUUID(), binding, "c".repeat(64), 0,
				NOW, NOW.plus(VALIDITY), null, null, null, NOW.plusSeconds(120));
		challengeStore.replaceCurrent(mfa, NOW);

		assertTrue(challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(120)) >= 1);
		assertTrue(challengeStore.findCurrentMfaByUser(account.getUserID()).isEmpty());
		assertEquals(1, challengeStore.findRecentMfaIssuances(account.getUserID(), NOW.minus(WINDOW), 10).size());
	}

	private void issue() {
		smsChallenges.createMfaChallenge(VALIDITY, 1, WINDOW, COOLDOWN, current -> {
			assertEquals(account.getUserID(), current.getUserID());
			assertEquals(account.getPhoneNumber(), current.getPhoneNumber());
		});
	}

	private String sentCode() {
		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(messageSender).send(eq(MFA_ENROLLMENT), eq(account.getPhoneNumber()), code.capture());
		return code.getValue();
	}
}
