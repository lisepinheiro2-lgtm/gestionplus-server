package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

class AccountInvitationExpirationPostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Test
	void expirationRejectsActivationAndClearsAllPreparedAccess() {
		UUID userID = preparePendingAccount();
		accessService.addStructureAccess(userID, createStructure(), Set.of(UserRole.TEAM_LEAD));
		SentInvitation message = sendInitial(userID);
		jdbcTemplate.update("""
				UPDATE account_invitations SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE invitation_id = ?
				""", message.invitationID());
		allowActivation(userID, message);
		SecurityContextHolder.clearContext();

		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "verified-proof"));
		invitations.expireInvitation(message.invitationID());

		assertDisabledWithoutAccess(userID);
	}

	@Test
	void anActivationCompletedBeforeTheDeadlineSurvivesAnExpirationWaitingForItsCommit() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		Instant deadline = invitationStore.findByAccount(userID).orElseThrow().expiresAt();
		AtomicReference<Instant> now = controlTime(deadline.minusSeconds(1));
		SecurityContextHolder.clearContext();

		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<?> expiration = new TransactionTemplate(transactionManager).execute(transaction -> {
				accountStore.findByIdForUpdate(userID).orElseThrow();
				activate(userID, message, "verified-proof");
				now.set(deadline);
				int pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				Future<?> pending = worker.submit(() -> withLockTimeout(
						() -> invitations.expireInvitation(message.invitationID())));
				awaitBlockedBy(pid, pending);
				return pending;
			});
			expiration.get(15, TimeUnit.SECONDS);
		}

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertTrue(credentials.existsById(userID));
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		assertEquals(1, membershipStore.findByUser(userID).size());
		assertEquals(1, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(0, successfulAudits(userID, "INVITATION_EXPIRE"));
	}

	@Test
	void anExpirationHoldingTheAccountLockPreventsAConcurrentActivation() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		Instant deadline = invitationStore.findByAccount(userID).orElseThrow().expiresAt();
		AtomicReference<Instant> now = controlTime(deadline);
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, account(userID));
		SecurityContextHolder.clearContext();

		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<Boolean> activation = new TransactionTemplate(transactionManager).execute(transaction -> {
				invitations.expireInvitation(message.invitationID());
				now.set(deadline.minusSeconds(1));
				int pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				Future<Boolean> pending = worker.submit(() -> activateIfAllowed(userID, message));
				awaitBlockedBy(pid, pending);
				return pending;
			});
			assertFalse(activation.get(15, TimeUnit.SECONDS));
		}

		assertDisabledWithoutAccess(userID);
		assertFalse(credentials.existsById(userID));
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(0, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(1, successfulAudits(userID, "INVITATION_EXPIRE"));
	}

	@Test
	void crossingTheDeadlineDuringIdentityVerificationLetsExpirationWin() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		Instant deadline = invitationStore.findByAccount(userID).orElseThrow().expiresAt();
		AtomicReference<Instant> now = controlTime(deadline.minusSeconds(1));
		AtomicInteger pid = new AtomicInteger();
		CountDownLatch verifying = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			pid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
			verifying.countDown();
			assertTrue(release.await(5, TimeUnit.SECONDS));
			return true;
		}).when(verifier).verify(message.invitationID(), userID, account(userID).getPhoneNumber(), "verified-proof");
		SecurityContextHolder.clearContext();

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> activation = workers.submit(() -> activateIfAllowed(userID, message));

			try {
				assertTrue(verifying.await(5, TimeUnit.SECONDS));
				now.set(deadline);
				Future<?> expiration = workers.submit(() -> withLockTimeout(
						() -> invitations.expireInvitation(message.invitationID())));
				awaitBlockedBy(pid.get(), expiration);
				release.countDown();
				assertFalse(activation.get(15, TimeUnit.SECONDS));
				expiration.get(15, TimeUnit.SECONDS);
			} finally {
				release.countDown();
			}

		}

		assertDisabledWithoutAccess(userID);
		assertFalse(credentials.existsById(userID));
		assertEquals(0, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(1, successfulAudits(userID, "INVITATION_EXPIRE"));
	}

	private AtomicReference<Instant> controlTime(Instant initial) {
		AtomicReference<Instant> now = new AtomicReference<>(initial);
		when(clock.instant()).thenAnswer(invocation -> now.get());
		return now;
	}

	private boolean activateIfAllowed(UUID userID, SentInvitation message) {

		try {
			withLockTimeout(() -> activate(userID, message, "verified-proof"));
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		}

	}

	private void awaitBlockedBy(int pid, Future<?> waiting) {
		Instant deadline = Instant.now().plusSeconds(3);

		while (!waiting.isDone() && Instant.now().isBefore(deadline)) {
			boolean blocked = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
					SELECT EXISTS (
					    SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
					)
					""", Boolean.class, pid));

			if (blocked) {
				return;
			}

			try {
				Thread.sleep(20);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(interrupted);
			}

		}

		throw new AssertionError("L’opération concurrente doit attendre le verrou du compte.");
	}
}
