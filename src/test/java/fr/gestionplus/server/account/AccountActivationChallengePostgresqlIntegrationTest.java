package fr.gestionplus.server.account;

import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.ACCOUNT_ACTIVATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.UserAccountStatus;

@TestPropertySource(properties = "gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class AccountActivationChallengePostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

	@Autowired
	private AuditStore auditStore;
	@Autowired
	private InvitationTokenIssuer tokenIssuer;

	private String token;

	@BeforeEach
	void controlTime() {
		when(clock.instant()).thenReturn(NOW);
		token = tokenIssuer.token(UUID.randomUUID(), UUID.randomUUID());
	}

	@Test
	void anAnonymousHolderCanRequestACodeWithoutActivatingTheAccount() {
		UUID userID = preparePendingAccount();
		Invitation invitation = prepareInvitation(userID);
		String phone = account(userID).getPhoneNumber();
		SecurityContextHolder.clearContext();

		doAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			assertTrue(challengeStore.findCurrentByInvitationAndUser(invitation.invitationID(), userID).isPresent());
			return null;
		}).when(messageSender).send(eq(ACCOUNT_ACTIVATION), eq(phone), anyString());

		activation.requestActivationChallenge(invitation.invitationID(), token);

		verify(messageSender).send(eq(ACCOUNT_ACTIVATION), eq(phone), anyString());
		SmsIdentityChallenge challenge = challengeStore
				.findCurrentByInvitationAndUser(invitation.invitationID(), userID).orElseThrow();
		assertEquals(phone, challenge.binding().phoneNumber());
		assertNull(challenge.consumedAt());
		assertNull(challenge.revokedAt());
		assertEquals(1, challengeStore.countByInvitationAndUser(invitation.invitationID(), userID));
		assertEquals(invitation, invitationStore.findByID(invitation.invitationID()).orElseThrow());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertFalse(credentials.existsById(userID));
		verifyNoInteractions(verifier, credentialService);
	}

	@Test
	void aWrongTokenIsDeniedAndAuditedWithoutCreatingOrSendingACode() {
		UUID userID = preparePendingAccount();
		Invitation invitation = prepareInvitation(userID);
		SecurityContextHolder.clearContext();

		assertThrows(AccessDeniedException.class,
				() -> activation.requestActivationChallenge(invitation.invitationID(), "b".repeat(43)));

		assertEquals(0, challengeStore.countByInvitationAndUser(invitation.invitationID(), userID));
		verifyNoInteractions(messageSender, verifier, credentialService);
		assertSmsAudit(invitation.invitationID(), AuditResult.DENIED, AuditReason.ACCESS_NOT_AUTHORIZED);
	}

	@Test
	void anInvitationIsDeniedAtItsExactExpiryWithoutSendingACode() {
		UUID userID = preparePendingAccount();
		Invitation invitation = prepareInvitation(userID);
		when(clock.instant()).thenReturn(invitation.expiresAt());
		SecurityContextHolder.clearContext();

		assertThrows(AccessDeniedException.class,
				() -> activation.requestActivationChallenge(invitation.invitationID(), token));

		assertEquals(0, challengeStore.countByInvitationAndUser(invitation.invitationID(), userID));
		verifyNoInteractions(messageSender, verifier, credentialService);
		assertSmsAudit(invitation.invitationID(), AuditResult.DENIED, AuditReason.ACCESS_NOT_AUTHORIZED);
	}

	@Test
	void aResendConflictIsAuditedAndTheSameTokenWorksAfterSixtySeconds() {
		UUID userID = preparePendingAccount();
		Invitation invitation = prepareInvitation(userID);
		UUID invitationID = invitation.invitationID();
		SecurityContextHolder.clearContext();
		activation.requestActivationChallenge(invitationID, token);
		SmsIdentityChallenge initial = challengeStore.findCurrentByInvitationAndUser(invitationID, userID)
				.orElseThrow();

		when(clock.instant()).thenReturn(NOW.plusSeconds(59));
		assertThrows(AccountActivationConflictException.class,
				() -> activation.requestActivationChallenge(invitationID, token));

		assertEquals(initial, challengeStore.findCurrentByInvitationAndUser(invitationID, userID).orElseThrow());
		assertEquals(1, challengeStore.countByInvitationAndUser(invitationID, userID));
		verify(messageSender).send(eq(ACCOUNT_ACTIVATION), anyString(), anyString());
		assertSmsAudit(invitationID, AuditResult.FAILURE, AuditReason.OPERATION_FAILED);

		when(clock.instant()).thenReturn(NOW.plusSeconds(60));
		activation.requestActivationChallenge(invitationID, token);

		assertEquals(2, challengeStore.countByInvitationAndUser(invitationID, userID));
		verify(messageSender, times(2)).send(eq(ACCOUNT_ACTIVATION), anyString(), anyString());
		assertEquals(invitation, invitationStore.findByID(invitationID).orElseThrow());
	}

	@Test
	void aFourthSmsRequestIsRefusedAndAuditedWithoutAnotherSend() {
		UUID userID = preparePendingAccount();
		UUID invitationID = prepareInvitation(userID).invitationID();
		SecurityContextHolder.clearContext();

		for (int attempt = 0; attempt < 3; attempt++) {
			when(clock.instant()).thenReturn(NOW.plusSeconds(60L * attempt));
			activation.requestActivationChallenge(invitationID, token);
		}

		SmsIdentityChallenge current = challengeStore.findCurrentByInvitationAndUser(invitationID, userID)
				.orElseThrow();
		when(clock.instant()).thenReturn(NOW.plusSeconds(180));

		assertThrows(AccountActivationConflictException.class,
				() -> activation.requestActivationChallenge(invitationID, token));

		assertEquals(3, challengeStore.countByInvitationAndUser(invitationID, userID));
		assertEquals(current, challengeStore.findCurrentByInvitationAndUser(invitationID, userID).orElseThrow());
		verify(messageSender, times(3)).send(eq(ACCOUNT_ACTIVATION), anyString(), anyString());
		assertSmsAudit(invitationID, AuditResult.FAILURE, AuditReason.OPERATION_FAILED);
	}

	@Test
	void aFailedSendIsCompensatedAndAuditedWithoutCopyingSecrets() {
		UUID userID = preparePendingAccount();
		Invitation invitation = prepareInvitation(userID);
		UUID invitationID = invitation.invitationID();
		IllegalStateException failure = new IllegalStateException("token=" + token + " code=654321");
		doThrow(failure).when(messageSender).send(eq(ACCOUNT_ACTIVATION), anyString(), anyString());
		SecurityContextHolder.clearContext();

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> activation.requestActivationChallenge(invitationID, token)));

		assertTrue(challengeStore.findCurrentByInvitationAndUser(invitationID, userID).isEmpty());
		assertEquals(1, challengeStore.countByInvitationAndUser(invitationID, userID));
		assertEquals(1, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM sms_identity_challenges
				WHERE invitation_id = ? AND user_id = ? AND revoked_at IS NOT NULL
				""", Integer.class, invitationID, userID));
		assertEquals(invitation, invitationStore.findByID(invitationID).orElseThrow());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertFalse(credentials.existsById(userID));
		verifyNoInteractions(verifier, credentialService);

		AuditEvent event = assertSmsAudit(invitationID, AuditResult.FAILURE, AuditReason.OPERATION_FAILED);
		String recorded = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_events WHERE event_id = ?",
				String.class, event.getEventId());
		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(messageSender).send(eq(ACCOUNT_ACTIVATION), anyString(), code.capture());
		assertFalse(recorded.contains(token));
		assertFalse(recorded.contains("654321"));
		assertFalse(recorded.contains(code.getValue()));
	}

	private Invitation prepareInvitation(UUID userID) {
		Invitation invitation = new Invitation(UUID.randomUUID(), userID, structureID,
				InvitationTokenIssuer.digest(token), NOW.plusSeconds(3600), null);
		invitationStore.save(invitation);
		return invitation;
	}

	private AuditEvent assertSmsAudit(UUID invitationID, AuditResult result, AuditReason reason) {
		List<UUID> eventIDs = jdbcTemplate.queryForList("""
				SELECT event.event_id FROM audit_events event JOIN audit_targets target
				ON event.event_id = target.event_id
				WHERE event.action = 'INVITATION_SMS_REQUEST'
				    AND target.target_type = 'INVITATION' AND target.target_id = ?
				""", UUID.class, invitationID);
		assertEquals(1, eventIDs.size());
		AuditEvent event = auditStore.findById(eventIDs.getFirst()).orElseThrow();
		assertEquals(AuditAction.INVITATION_SMS_REQUEST, event.getAction());
		assertEquals(result, event.getResult());
		assertEquals(AuditMetadata.because(reason), event.getMetadata());
		assertEquals(Set.of(new AuditTarget(AuditTargetType.INVITATION, invitationID)), event.getTargets());
		assertNull(event.getActorUserId());
		return event;
	}
}
