package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fr.gestionplus.server.account.AccountInvitationDelivery.Status;
import fr.gestionplus.server.account.AccountInvitationDeliveryStore.Claim;
import fr.gestionplus.server.account.AccountInvitationDeliveryStore.Request;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.organization.StructureMembershipStore;

class AccountInvitationDeliveryTest {

	private final AccountInvitationDeliveryStore deliveries = mock(AccountInvitationDeliveryStore.class);
	private final AccountInvitationStore invitations = mock(AccountInvitationStore.class);
	private final AccountStore accounts = mock(AccountStore.class);
	private final AccountInvitationScope scope = mock(AccountInvitationScope.class);
	private final StructureMembershipStore memberships = mock(StructureMembershipStore.class);
	private final AccountInvitationSender sender = mock(AccountInvitationSender.class);
	private final InvitationTokenIssuer tokens = mock(InvitationTokenIssuer.class);
	private final AuditService audit = mock(AuditService.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final MailDeliveryExecutor executor = mock(MailDeliveryExecutor.class);
	private final AccountInvitationDelivery worker = new AccountInvitationDelivery(deliveries, invitations, accounts,
			scope, memberships, sender, tokens, audit, transactions, executor);
	private final String token = "a".repeat(43);
	private final Request request = new Request(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
			UUID.randomUUID(), UUID.randomUUID(), 0, 0, "test@example.invalid", AuditAction.INVITATION_CREATE, false,
			InvitationTokenIssuer.digest(token), Map.of());
	private final Claim claim = new Claim(request, 1);

	@BeforeEach
	void prepareCommittedReservation() {
		when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
		when(deliveries.claim(request.requestID())).thenReturn(Optional.of(claim));
		when(deliveries.lockClaim(claim)).thenReturn(Optional.of(request));
		when(deliveries.complete(claim)).thenReturn(true);
		when(deliveries.retry(claim)).thenReturn(true);
		when(deliveries.cancel(claim)).thenReturn(true);
		when(scope.lockAndAuthorize(request.actorID(), request.actorVersion(), request.userID(), request.structureID(),
				request.roles())).thenReturn(UserAccount.pending(request.userID(), UUID.randomUUID(), "Exemple", "Test",
						request.email(), "0600000000"));
		when(tokens.token(request.requestID(), request.invitationID())).thenReturn(token);
		when(sender.send(request.email(), request.invitationID(), token)).thenReturn(true);
	}

	@AfterEach
	void clearTransactionState() {
		TransactionSynchronizationManager.clear();
	}

	@Test
	void sendsOnlyAfterReservationAndAuthorizationHaveCommitted() {
		when(sender.send(request.email(), request.invitationID(), token)).thenAnswer(call -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			verify(transactions, times(2)).commit(any());
			verify(invitations, never()).save(any());
			return true;
		});

		assertEquals(Status.SENT, worker.deliver(request.requestID()));

		verify(scope, times(2)).lockAndAuthorize(request.actorID(), request.actorVersion(), request.userID(),
				request.structureID(), request.roles());
		verify(transactions, times(3)).commit(any());
		verify(deliveries).complete(claim);
	}

	@Test
	void neverStartsDeliveryInsideAnActiveBusinessTransaction() {
		TransactionSynchronizationManager.setActualTransactionActive(true);

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verifyNoInteractions(deliveries, sender, transactions, scope);
	}

	@Test
	void aBusyReservationDoesNotSendAgain() {
		when(deliveries.claim(request.requestID())).thenReturn(Optional.empty());
		when(deliveries.findById(request.requestID())).thenReturn(Optional.of(request));

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verifyNoInteractions(sender, scope);
	}

	@Test
	void anAlreadyCompletedRequestDoesNotSendAgain() {
		when(deliveries.claim(request.requestID())).thenReturn(Optional.empty());
		when(deliveries.wasSent(request.requestID())).thenReturn(true);

		assertEquals(Status.SENT, worker.deliver(request.requestID()));

		verifyNoInteractions(sender, scope);
	}

	@Test
	void aRefusedPermissionCancelsOnlyTheOwnedReservationWithoutSending() {
		doThrow(new AccessDeniedException("Refus simulé")).when(scope).lockAndAuthorize(request.actorID(),
				request.actorVersion(), request.userID(), request.structureID(), request.roles());

		assertEquals(Status.CANCELLED, worker.deliver(request.requestID()));

		verify(deliveries).cancel(claim);
		verifyNoInteractions(sender);
	}

	@Test
	void aChangedDeliveryKeyCannotSendADifferentToken() {
		when(tokens.token(request.requestID(), request.invitationID())).thenReturn("b".repeat(43));

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verify(deliveries).retry(claim);
		verifyNoInteractions(sender);
	}

	@Test
	void aFailedAuthorizationCommitPreventsSmtp() {
		doNothing().doThrow(new IllegalStateException("Commit indisponible")).doNothing()
				.when(transactions).commit(any());

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verify(deliveries).retry(claim);
		verifyNoInteractions(sender);
		verify(invitations, never()).save(any());
	}

	@Test
	void permissionsRevokedDuringSmtpPreventFinalization() {
		UserAccount pending = UserAccount.pending(request.userID(), UUID.randomUUID(), "Exemple", "Test",
				request.email(), "0600000000");
		when(scope.lockAndAuthorize(request.actorID(), request.actorVersion(), request.userID(), request.structureID(),
				request.roles())).thenReturn(pending).thenThrow(new AccessDeniedException("Droits révoqués"));

		assertEquals(Status.CANCELLED, worker.deliver(request.requestID()));

		verify(sender).send(request.email(), request.invitationID(), token);
		verify(deliveries).cancel(claim);
		verify(invitations, never()).save(any());
	}

	@Test
	void aFailedSmtpAttemptRetainsTheRequestWithoutPublishingAnInvitation() {
		when(sender.send(request.email(), request.invitationID(), token)).thenReturn(false);

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verify(deliveries).retry(claim);
		verify(deliveries, never()).complete(any());
		verify(invitations, never()).save(any());
	}

	@Test
	void anSmtpExceptionKeepsTheOwnedRequestRetryable() {
		when(sender.send(request.email(), request.invitationID(), token))
				.thenThrow(new IllegalStateException("Détail SMTP privé"));

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verify(deliveries).retry(claim);
		verify(deliveries, never()).complete(any());
		verify(invitations, never()).save(any());
	}

	@Test
	void aLostReservationCannotCommitTheInvitationAfterSmtpAcceptance() {
		when(deliveries.complete(claim)).thenReturn(false);
		when(deliveries.retry(claim)).thenReturn(false);
		when(deliveries.findById(request.requestID())).thenReturn(Optional.of(request));

		assertEquals(Status.PENDING, worker.deliver(request.requestID()));

		verify(transactions).rollback(any());
		verify(sender).send(request.email(), request.invitationID(), token);
	}
}
