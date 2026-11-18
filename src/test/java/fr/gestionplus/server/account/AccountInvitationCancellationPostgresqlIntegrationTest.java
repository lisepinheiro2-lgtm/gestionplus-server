package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

class AccountInvitationCancellationPostgresqlIntegrationTest
		extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Test
	void cancellationRemovesEveryPreparedMembershipAndKeepsAdministrativeTenantMembership() {
		UUID userID = preparePendingAccount();
		accessService.addStructureAccess(userID, createStructure(), Set.of(UserRole.TEAM_LEAD));
		sendInitial(userID);

		invitations.cancelInvitation(userID, structureID);

		assertDisabledWithoutAccess(userID);
	}

	@Test
	void lastAccessRevocationAlsoInvalidatesTheInvitation() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);

		accessService.revokeStructureAccess(userID, structureID);

		assertDisabledWithoutAccess(userID);
		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "verified-proof"));
	}

	@Test
	void cancelsAnInitialQueuedInvitationBeforeItHasAnyUsableLink() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);

		invitations.cancelInvitation(userID, structureID);
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertDisabledWithoutAccess(userID);
	}

	@Test
	void cancelsAQueuedReintegrationWithoutRestoringAnyPreparedRoles() {
		UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(), structureID);
		joinTenant(former.getUserID());
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.inviteForReintegration(former.getUserID(),
				structureID, Map.of(structureID, Set.of(UserRole.TEAM_LEAD)), null);

		invitations.cancelInvitation(former.getUserID(), structureID);
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertDisabledWithoutAccess(former.getUserID());
	}

	@Test
	void cancellationCanCommitWhileSmtpIsBlockedAndPreventsActivation() throws Exception {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		makePendingDeliveryDue(userID);
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch releaseSmtp = new CountDownLatch(1);
		doAnswer(call -> {
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			sending.countDown();
			assertTrue(releaseSmtp.await(10, TimeUnit.SECONDS));
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<AccountInvitationDelivery.Status> sendingResult = workers.submit(
					() -> deliveryWorker.deliver(pending.requestID()));

			try {
				assertTrue(sending.await(5, TimeUnit.SECONDS));
				Future<?> cancellation = workers.submit(() -> {
					authenticate(actor);

					try {
						withLockTimeout(() -> invitations.cancelInvitation(userID, structureID));
					} finally {
						SecurityContextHolder.clearContext();
					}

				});
				cancellation.get(5, TimeUnit.SECONDS);
			} finally {
				releaseSmtp.countDown();
			}

			assertEquals(AccountInvitationDelivery.Status.CANCELLED, sendingResult.get(10, TimeUnit.SECONDS));
		}

		assertDisabledWithoutAccess(userID);
		assertThrows(AccessDeniedException.class, () -> activate(userID, sent.getFirst(), "verified-proof"));
		assertEquals(0, successfulAudits(userID, "INVITATION_CREATE"));
	}
}
