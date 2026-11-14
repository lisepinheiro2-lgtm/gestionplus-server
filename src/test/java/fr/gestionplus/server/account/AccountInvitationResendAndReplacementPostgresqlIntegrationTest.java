package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.auth.UserAccountStatus;

class AccountInvitationResendAndReplacementPostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Test
	void unsuccessfulResendPreservesOldLinkAndSuccessfulResendInvalidatesIt() {
		UUID userID = preparePendingAccount();
		SentInvitation first = sendInitial(userID);
		Invitation initial = invitationStore.findByAccount(userID).orElseThrow();
		doReturn(false).when(sender).send(anyString(), any(), anyString());

		AccountInvitationService.Delivery failed = invitations.resendInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		assertFalse(failed.sent());
		assertEquals(initial, invitationStore.findByAccount(userID).orElseThrow());

		allowDelivery();
		makePendingDeliveryDue(userID);
		assertTrue(awaitDelivered(invitations.resendInvitation(userID, structureID)).sent());
		SentInvitation resent = sent.getLast();
		Invitation latest = invitationStore.findByAccount(userID).orElseThrow();
		assertNotEquals(first.token(), resent.token());
		assertNotEquals(first.invitationID(), resent.invitationID());
		assertTrue(invitationStore.findByID(first.invitationID()).isEmpty());
		assertFalse(latest.expiresAt().isBefore(resent.deliveredAt().plus(Duration.ofDays(7)).minusMillis(1)));
		assertThrows(AccessDeniedException.class, () -> activate(userID, first, "verified-proof"));

		allowActivation(userID, resent);
		activate(userID, resent, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
	}

	@Test
	void replacementFailureLeavesTheAccountPendingWithoutAnyUsableLink() {
		UUID userID = preparePendingAccount();
		SentInvitation first = sendInitial(userID);
		doReturn(false).when(sender).send(anyString(), any(), anyString());

		assertFalse(invitations.replaceInvitation(userID, structureID, "corrected@example.invalid").sent());

		assertEquals("corrected@example.invalid", account(userID).getEmail());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertEquals(1, membershipStore.findByUser(userID).size());
		Invitation pendingReplacement = invitationStore.findByAccount(userID).orElseThrow();
		assertNull(pendingReplacement.tokenDigest());
		assertNull(pendingReplacement.expiresAt());
		assertTrue(invitationStore.findByID(first.invitationID()).isEmpty());
		assertThrows(AccessDeniedException.class, () -> activate(userID, first, "verified-proof"));

		allowDelivery();
		assertTrue(
				awaitDelivered(invitations.replaceInvitation(userID, structureID, "corrected@example.invalid")).sent());
		assertEquals("corrected@example.invalid", sent.getLast().email());
		assertEquals(sent.getLast().invitationID(), invitationStore.findByAccount(userID).orElseThrow().invitationID());
	}

	@Test
	void replacementRevocationSurvivesFailureOfTheSendingTransaction() {
		UUID userID = preparePendingAccount();
		SentInvitation first = sendInitial(userID);
		doThrow(new IllegalStateException("Échec simulé après invalidation"))
				.when(sender).send(anyString(), any(), anyString());

		AccountInvitationService.Delivery delivery = invitations.replaceInvitation(userID, structureID,
				"corrected@example.invalid");
		assertFalse(delivery.sent());
		assertTrue(delivery.queued());
		assertTrue(deliveryStore.findById(delivery.requestID()).isPresent());

		assertTrue(invitationStore.findByID(first.invitationID()).isEmpty());
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
	}
}
