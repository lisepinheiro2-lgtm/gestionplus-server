package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.auth.UserAccountStatus;

class AccountInvitationInitialDeliveryPostgresqlIntegrationTest
		extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Test
	void aRollbackOfTheCallingTransactionCannotSendAnInvitation() {
		UUID userID = preparePendingAccount();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			AccountInvitationService.Delivery delivery = invitations.sendInitialInvitation(userID, structureID);
			assertTrue(delivery.queued());
			assertFalse(delivery.sent());
			assertTrue(sent.isEmpty());
			transaction.setRollbackOnly();
		});

		assertTrue(sent.isEmpty());
		assertTrue(deliveryStore.findPending(userID).isEmpty());
		assertTrue(invitationStore.findByAccount(userID).isEmpty());
		assertEquals(0, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account_invitation_deliveries WHERE user_id = ?
				""", Integer.class, userID));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void commitsTheDeliveryRequestBeforeAnyEmailAndKeepsTheSameTokenAfterARollback(boolean replacement) {
		UUID userID = preparePendingAccount();

		if (replacement) {
			sendInitial(userID);
			sent.clear();
		}

		doAnswer(call -> {
			assertEquals(1, jdbcTemplate.queryForObject("""
					SELECT COUNT(*) FROM account_invitation_deliveries WHERE user_id = ? AND state = 'PENDING'
					""", Integer.class, userID));
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			return true;
		}).when(sender).send(anyString(), any(), anyString());
		doThrow(new IllegalStateException("Échec simulé après acceptation SMTP"))
				.doCallRealMethod().when(deliveryStore).complete(any());

		AccountInvitationService.Delivery pending = replacement
				? invitations.replaceInvitation(userID, structureID, "corrected@example.invalid")
				: invitations.sendInitialInvitation(userID, structureID);
		awaitFirstVerdict(userID);

		assertTrue(pending.queued());
		assertEquals(1, sent.size());
		SentInvitation accepted = sent.getFirst();
		assertTrue(deliveryStore.findById(pending.requestID()).isPresent());

		if (replacement) {
			assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		} else {
			assertTrue(invitationStore.findByAccount(userID).isEmpty());
		}

		assertThrows(InvitationDeliveryPendingException.class,
				() -> activate(userID, accepted, "verified-proof"));
		SentInvitation forged = new SentInvitation(accepted.email(), accepted.invitationID(),
				"x".repeat(43), accepted.deliveredAt());
		assertThrows(AccessDeniedException.class, () -> activate(userID, forged, "verified-proof"));

		makePendingDeliveryDue(userID);
		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
		SentInvitation retried = sent.getLast();
		assertEquals(accepted.token(), retried.token());
		assertEquals(accepted.invitationID(), retried.invitationID());
		assertTrue(deliveryStore.findById(pending.requestID()).isEmpty());
		assertEquals(accepted.invitationID(), invitationStore.findByAccount(userID).orElseThrow().invitationID());
		allowActivation(userID, retried);
		activate(userID, retried, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
	}

	@Test
	void aFullExecutorLeavesTheInitialSendToTheScheduledPass() throws Exception {
		UUID userID = preparePendingAccount();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(3);

		for (int i = 0; i < 3; i++) {
			assertTrue(deliveryExecutor.trySubmit(() -> {
				started.countDown();
				awaitRelease(release);
			}));
		}

		assertTrue(started.await(5, TimeUnit.SECONDS));

		AccountInvitationService.Delivery delivery = invitations.sendInitialInvitation(userID, structureID);
		assertTrue(delivery.queued());
		assertFalse(delivery.sent());
		verify(sender, never()).send(anyString(), any(), anyString());
		assertTrue(deliveryStore.findPending(userID).isPresent());

		release.countDown();
		awaitAvailableDeliverySlot();
		deliveryWorker.retryPending();
		awaitSentState(delivery.requestID());
		assertEquals(1, sent.size());
		assertFalse(delivery.sent());
	}

	@Test
	void smtpRunsWithoutATransactionOrBusinessLocks() {
		UUID userID = preparePendingAccount();
		doAnswer(call -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
				jdbcTemplate.queryForObject("""
						SELECT structure_id FROM organizational_structures WHERE structure_id = ? FOR UPDATE NOWAIT
						""", UUID.class, structureID);
				jdbcTemplate.queryForObject("""
						SELECT user_id FROM user_accounts WHERE user_id = ? FOR UPDATE NOWAIT
						""", UUID.class, userID);
				jdbcTemplate.queryForObject("""
						SELECT user_id FROM user_accounts WHERE user_id = ? FOR UPDATE NOWAIT
						""", UUID.class, actor.getUserID());
			});
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		assertTrue(awaitDelivered(invitations.sendInitialInvitation(userID, structureID)).sent());
		assertTrue(invitationStore.findByAccount(userID).isPresent());
	}

	private void awaitRelease(CountDownLatch latch) {

		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(failure);
		}

	}

	private void awaitAvailableDeliverySlot() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;

		while (!deliveryExecutor.trySubmit(() -> {
		})) {

			if (System.currentTimeMillis() > deadline) {
				fail("jeton jamais rendu");
			}

			Thread.sleep(10);

		}

	}

	private void awaitSentState(UUID requestID) {
		long deadline = System.currentTimeMillis() + 5000;

		while (!deliveryStore.wasSent(requestID)) {

			if (System.currentTimeMillis() > deadline) {
				fail("état SENT jamais enregistré");
			}

			try {
				Thread.sleep(10);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(failure);
			}

		}

	}
}
