package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;

class AccountInvitationDeliveryRetryPostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Test
	void failedDeliveryIsRetainedAndCanBeRetriedWithoutTheOriginalSession() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		assertTrue(pending.queued());
		assertEquals(1, jdbcTemplate.queryForObject("""
				SELECT attempts FROM account_invitation_deliveries WHERE request_id = ?
				""", Integer.class, pending.requestID()));
		allowDelivery();
		SecurityContextHolder.clearContext();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));

		assertEquals(1, sent.size());
		assertEquals(actor.getUserID(), jdbcTemplate.queryForObject("""
				SELECT actor_user_id FROM audit_events event JOIN audit_targets target
				ON event.event_id = target.event_id
				WHERE action = 'INVITATION_CREATE' AND result = 'SUCCESS' AND target.target_id = ?
				""", UUID.class, sent.getFirst().invitationID()));
	}

	@Test
	void pendingDeliveryCannotSurviveARevokedActorOrDisabledTarget() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		new TransactionTemplate(transactionManager).executeWithoutResult(
				transaction -> accountStore.advanceSessionVersion(actor.getUserID()));
		allowDelivery();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());

		authenticate(account(actor.getUserID()));
		AccountInvitationService.Delivery replacement = invitations.sendInitialInvitation(userID, structureID);
		assertTrue(awaitDelivered(replacement).sent());
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery resend = invitations.resendInvitation(userID, structureID);
		invitations.cancelInvitation(userID, structureID);
		int delivered = sent.size();
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(resend.requestID()));
		assertEquals(delivered, sent.size());
		assertDisabledWithoutAccess(userID);
	}

	@Test
	void rechecksCurrentActorPermissionsEvenWhenTheSessionVersionHasNotChanged() {
		UUID userID = preparePendingAccount();
		UserAccount secretary = createAccount(UserAccountStatus.ACTIVE, Set.of(), structureID);
		joinTenant(secretary.getUserID());
		accessService.addStructureAccess(secretary.getUserID(), structureID, Set.of(UserRole.SECRETARY));
		UserAccount principal = account(secretary.getUserID());
		authenticate(principal);
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		authenticate(actor);
		membershipStore.save(new StructureMembership(secretary.getUserID(), structureID, Set.of(UserRole.EMPLOYEE)));
		assertEquals(principal.getSessionVersion(), account(secretary.getUserID()).getSessionVersion());
		allowDelivery();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
	}

	@Test
	void twoWorkersCannotSendTheSamePendingRequestConcurrently() throws Exception {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		allowDelivery();
		makePendingDeliveryDue(userID);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<AccountInvitationDelivery.Status> first = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return deliveryWorker.deliver(pending.requestID());
			});
			Future<AccountInvitationDelivery.Status> second = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return deliveryWorker.deliver(pending.requestID());
			});
			AccountInvitationDelivery.Status firstResult = first.get(15, TimeUnit.SECONDS);
			AccountInvitationDelivery.Status secondResult = second.get(15, TimeUnit.SECONDS);
			assertTrue(firstResult == AccountInvitationDelivery.Status.SENT
					|| secondResult == AccountInvitationDelivery.Status.SENT);
			assertTrue(Set.of(AccountInvitationDelivery.Status.SENT, AccountInvitationDelivery.Status.PENDING)
					.contains(firstResult));
			assertTrue(Set.of(AccountInvitationDelivery.Status.SENT, AccountInvitationDelivery.Status.PENDING)
					.contains(secondResult));
		}

		assertEquals(1, sent.size());
		assertTrue(deliveryStore.wasSent(pending.requestID()));
	}

	@Test
	void aReservationOrRetryDelayPreventsImmediateRepeatedSmtpAttempts() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.PENDING, deliveryWorker.deliver(pending.requestID()));
		assertFalse(deliveryStore.findDue().contains(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertEquals(1, deliveryAttempts(pending.requestID()));

		makePendingDeliveryDue(userID);
		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
		assertEquals(2, deliveryAttempts(pending.requestID()));
	}

	@Test
	void aProcessInterruptionLeavesARecoverableReservationWithTheSameToken() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		makePendingDeliveryDue(userID);
		doAnswer(call -> {
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			throw new AssertionError("Interruption du processus simulée");
		}).when(sender).send(anyString(), any(), anyString());

		assertThrows(AssertionError.class, () -> deliveryWorker.deliver(pending.requestID()));
		assertTrue(deliveryStore.findById(pending.requestID()).isPresent());
		assertTrue(invitationStore.findByAccount(userID).isEmpty());
		assertFalse(deliveryStore.findDue().contains(pending.requestID()));
		SentInvitation interrupted = sent.getFirst();
		allowDelivery();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
		assertEquals(interrupted.token(), sent.getLast().token());
		assertEquals(interrupted.invitationID(), sent.getLast().invitationID());
		assertEquals(1, successfulAudits(userID, "INVITATION_CREATE"));
	}

	@Test
	void anExpiredWorkerCannotOverwriteTheNewWorkersCompletion() throws Exception {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);
		makePendingDeliveryDue(userID);
		AtomicInteger sends = new AtomicInteger();
		AtomicReference<String> firstToken = new AtomicReference<>();
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch releaseSmtp = new CountDownLatch(1);
		doAnswer(call -> {

			if (sends.incrementAndGet() == 1) {
				firstToken.set(call.getArgument(2));
				sending.countDown();
				assertTrue(releaseSmtp.await(10, TimeUnit.SECONDS));
				return false;
			}

			assertEquals(firstToken.get(), call.getArgument(2));
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
			Future<AccountInvitationDelivery.Status> oldWorker = workers.submit(
					() -> deliveryWorker.deliver(pending.requestID()));

			try {
				assertTrue(sending.await(5, TimeUnit.SECONDS));
				assertEquals(AccountInvitationDelivery.Status.PENDING, deliveryWorker.deliver(pending.requestID()));
				assertEquals(1, sends.get());
				makePendingDeliveryDue(userID);
				assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
			} finally {
				releaseSmtp.countDown();
			}

			assertEquals(AccountInvitationDelivery.Status.SENT, oldWorker.get(10, TimeUnit.SECONDS));
		}

		assertEquals(2, sends.get());
		assertEquals(3, deliveryAttempts(pending.requestID()));
		assertTrue(deliveryStore.wasSent(pending.requestID()));
		assertEquals(1, successfulAudits(userID, "INVITATION_CREATE"));
	}

	private int deliveryAttempts(UUID requestID) {
		return jdbcTemplate.queryForObject("""
				SELECT attempts FROM account_invitation_deliveries WHERE request_id = ?
				""", Integer.class, requestID);
	}
}
