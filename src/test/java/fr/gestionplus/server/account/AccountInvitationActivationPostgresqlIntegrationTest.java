package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.auth.MfaEnrollmentVerifier;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.persistence.UserCredentialEntity;

class AccountInvitationActivationPostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Autowired
	private MfaEnrollmentVerifier enrollmentVerifier;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void activationOnlyPersistsAndReturnsAProofWhenInitialMfaEnrollmentIsRequired(boolean mfaRequired) throws Exception {
		UUID userID = preparePendingAccount();
		if (mfaRequired) {
			addMfaRequiredRole(userID);
		}
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);

		Optional<String> proof = activation.completeActivation(message.invitationID(), message.token(), "verified-proof",
				"activated_" + userID, "valid-test-password");

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertEquals(mfaRequired, proof.isPresent());
		assertEquals(mfaRequired ? 1 : 0, enrollmentProofCount(userID));
		if (mfaRequired) {
			String supplied = proof.orElseThrow();
			assertTrue(supplied.matches("[A-Za-z0-9_-]{22}"));
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(supplied.getBytes(StandardCharsets.US_ASCII)));
			assertEquals(hash, jdbcTemplate.queryForObject(
					"SELECT proof_hash FROM account_mfa_enrollment_proofs WHERE user_id = ?", String.class, userID));
			long version = account(userID).getSessionVersion();
			assertTrue(enrollmentVerifier.consumeVerifiedIdentity(userID, version, supplied));
			assertFalse(enrollmentVerifier.consumeVerifiedIdentity(userID, version, supplied));
		}
	}

	@Test
	void rollingBackActivationAlsoRollsBackTheEnrollmentProofAndChallengeConsumption() {
		UUID userID = preparePendingAccount();
		addMfaRequiredRole(userID);
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);

		assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					assertTrue(activation.completeActivation(message.invitationID(), message.token(), "verified-proof",
							"activated_" + userID, "valid-test-password").isPresent());
					throw new IllegalStateException("Activation fictive annulée après émission de la preuve.");
				}));

		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertEquals(0, enrollmentProofCount(userID));
		assertEquals(InvitationTokenIssuer.digest(message.token()),
				invitationStore.findByID(message.invitationID()).orElseThrow().tokenDigest());
		assertTrue(challengeStore.findCurrentByInvitationAndUser(message.invitationID(), userID).isPresent());
		assertFalse(credentials.existsById(userID));
	}

	@Test
	void twoConcurrentActivationsCannotConsumeTheSameInvitation() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> tryActivation(start, userID, message));
			Future<Boolean> second = workers.submit(() -> tryActivation(start, userID, message));
			assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
		}

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
	}

	@Test
	void anUnknownInvitationIsRejectedBeforeAnyPasswordCheck() {
		assertThrows(AccessDeniedException.class, () -> activation.completeActivation(UUID.randomUUID(),
				"x".repeat(43), "verified-proof", "activated_someone", "valid-test-password"));
		verify(credentialService, never()).validatePassword(anyString());
	}

	@Test
	void aWrongTokenIsRejectedBeforeAnyPasswordCheck() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);

		assertThrows(AccessDeniedException.class, () -> activation.completeActivation(message.invitationID(),
				"y".repeat(43), "verified-proof", "activated_" + userID, "valid-test-password"));
		verify(credentialService, never()).validatePassword(anyString());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertNotNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aUsernameCollisionRollsBackActivationWithoutConsumingTheInvitation(boolean reintegration) {
		UUID userID;
		SentInvitation message;

		if (reintegration) {
			UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(), structureID);
			userID = former.getUserID();
			joinTenant(userID);
			credentials.saveAndFlush(new UserCredentialEntity(new UserCredential(userID, "old-test-hash")));
			assertTrue(awaitDelivered(invitations.inviteForReintegration(userID, structureID,
					Map.of(structureID, Set.of(UserRole.EMPLOYEE)), null)).sent());
			message = sent.getLast();
		} else {
			userID = preparePendingAccount();
			message = sendInitial(userID);
		}

		allowActivation(userID, message);
		UserAccount other = createAccount(UserAccountStatus.ACTIVE, Set.of(), null);
		Invitation before = invitationStore.findByAccount(userID).orElseThrow();
		long version = account(userID).getSessionVersion();
		SecurityContextHolder.clearContext();

		AccountActivationConflictException failure = assertThrows(AccountActivationConflictException.class,
				() -> activation.completeActivation(message.invitationID(), message.token(), "verified-proof",
						" " + other.getUserName() + " ", "valid-test-password"));

		assertFalse(failure.getMessage().contains(other.getUserName()));
		assertFalse(failure.getMessage().contains(other.getUserID().toString()));
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertEquals(version, account(userID).getSessionVersion());
		assertEquals(before, invitationStore.findByAccount(userID).orElseThrow());

		if (reintegration) {
			assertEquals("old-test-hash", credentials.findById(userID).orElseThrow().toDomain().getPasswordHash());
		} else {
			assertFalse(credentials.existsById(userID));
		}

		assertEquals(1, membershipStore.findByUser(userID).size());
		assertEquals(0, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(0, successfulAudits(userID, "INVITATION_TOKEN_USE"));

		activate(userID, message, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertEquals(UserAccountStatus.ACTIVE, account(other.getUserID()).getStatu());
	}

	@Test
	void twoConcurrentActivationsCannotClaimTheSameUsername() throws Exception {
		UUID firstID = preparePendingAccount();
		UUID secondID = preparePendingAccount();
		SentInvitation firstMessage = sendInitial(firstID);
		SentInvitation secondMessage = sendInitial(secondID);
		allowActivation(firstID, firstMessage);
		allowActivation(secondID, secondMessage);
		String userName = "shared_" + UUID.randomUUID();
		CyclicBarrier start = new CyclicBarrier(2);
		boolean firstWon;
		boolean secondWon;

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> claimUsername(start, firstMessage, userName));
			Future<Boolean> second = workers.submit(() -> claimUsername(start, secondMessage, userName));
			firstWon = first.get(15, TimeUnit.SECONDS);
			secondWon = second.get(15, TimeUnit.SECONDS);
		}

		assertTrue(firstWon ^ secondWon);
		UUID winner = firstWon ? firstID : secondID;
		UUID loser = firstWon ? secondID : firstID;
		SentInvitation losingMessage = firstWon ? secondMessage : firstMessage;
		assertEquals(UserAccountStatus.ACTIVE, account(winner).getStatu());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(loser).getStatu());
		assertTrue(credentials.existsById(winner));
		assertFalse(credentials.existsById(loser));
		assertEquals(1, successfulAudits(winner, "ACCOUNT_ACTIVATE"));
		assertEquals(0, successfulAudits(loser, "ACCOUNT_ACTIVATE"));
		assertTrue(invitationStore.findByAccount(loser).orElseThrow().tokenDigest() != null);

		activate(loser, losingMessage, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(loser).getStatu());
	}

	private void addMfaRequiredRole(UUID userID) {
		jdbcTemplate.update("INSERT INTO structure_membership_roles (user_id, structure_id, role) VALUES (?, ?, 'R_H')",
				userID, structureID);
	}

	private int enrollmentProofCount(UUID userID) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_mfa_enrollment_proofs WHERE user_id = ?",
				Integer.class, userID);
	}

	private boolean claimUsername(CyclicBarrier start, SentInvitation message, String userName) throws Exception {
		start.await(5, TimeUnit.SECONDS);

		try {
			withLockTimeout(() -> activation.completeActivation(message.invitationID(), message.token(),
					"verified-proof", userName, "valid-test-password"));
			return true;
		} catch (AccountActivationConflictException conflict) {
			return false;
		}

	}

	private boolean tryActivation(CyclicBarrier start, UUID userID, SentInvitation message) throws Exception {
		start.await(5, TimeUnit.SECONDS);

		try {
			activate(userID, message, "verified-proof");
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		}

	}
}
