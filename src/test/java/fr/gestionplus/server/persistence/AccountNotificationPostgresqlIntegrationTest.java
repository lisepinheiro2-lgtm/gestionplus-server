package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import fr.gestionplus.server.account.AccountNotificationDelivery;
import fr.gestionplus.server.account.AccountNotificationStore;
import fr.gestionplus.server.account.AccountNotificationStore.Kind;
import fr.gestionplus.server.account.AccountNotificationStore.Notification;
import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.account.MailDeliveryExecutor;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.auth.UserAccount;

@Testcontainers
class AccountNotificationPostgresqlIntegrationTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
			.withDatabaseName("fictitious_account_notifications").withUsername("fictitious_migration")
			.withPassword(UUID.randomUUID().toString())
			.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
					new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
	private static final UUID LEGACY_RECIPIENT = UUID.randomUUID();
	private static final UUID LEGACY_TARGET = UUID.randomUUID();
	private static final UUID LEGACY_STRUCTURE = UUID.randomUUID();
	private static final OffsetDateTime LEGACY_DUE = OffsetDateTime.parse("2026-10-01T12:00:00Z");
	private static JdbcTemplate jdbcTemplate;
	private static DataSourceTransactionManager transactionManager;
	private static TransactionTemplate transaction;
	private static AccountNotificationStore notificationStore;
	private static List<Notification> migratedNotifications;
	private static List<OffsetDateTime> migratedDueDates;

	@BeforeAll
	static void migratePendingNotificationsFromV30() {
		var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("30").load().migrate();
		jdbcTemplate = new JdbcTemplate(dataSource);

		for (Kind kind : List.of(Kind.ACCOUNT_DEACTIVATION, Kind.ADMIN_REMOVED, Kind.ADMIN_GRANTED)) {
			jdbcTemplate.update("""
					INSERT INTO account_deactivation_notifications
					    (notification_id, actor_id, target_id, structure_id, kind, attempts, next_attempt_at)
					VALUES (?, ?, ?, ?, ?, 2, ?)
					""", UUID.randomUUID(), LEGACY_RECIPIENT, LEGACY_TARGET, LEGACY_STRUCTURE, kind.name(), LEGACY_DUE);
		}

		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		transactionManager = new DataSourceTransactionManager(dataSource);
		transaction = new TransactionTemplate(transactionManager);
		ProxyFactory proxy = new ProxyFactory(new PostgresqlAccountNotificationStore(jdbcTemplate));
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		notificationStore = (AccountNotificationStore) proxy.getProxy();
		migratedNotifications = notifications();
		migratedDueDates = jdbcTemplate.queryForList("SELECT next_attempt_at FROM account_deactivation_notifications",
				OffsetDateTime.class);
	}

	@BeforeEach
	void clearTheIsolatedQueue() {
		jdbcTemplate.update("DELETE FROM account_deactivation_notifications");
	}

	@Test
	void migrationPreservesHistoricalRecipientsTypesAttemptsAndReservationsWithoutInventingActors() {
		assertEquals(3, migratedNotifications.size());
		assertEquals(Set.of(Kind.ACCOUNT_DEACTIVATION, Kind.ADMIN_REMOVED, Kind.ADMIN_GRANTED),
				Set.copyOf(migratedNotifications.stream().map(Notification::kind).toList()));

		for (Notification notification : migratedNotifications) {
			assertNull(notification.actorUserID());
			assertEquals(LEGACY_RECIPIENT, notification.recipientUserID());
			assertEquals(LEGACY_TARGET, notification.targetUserID());
			assertEquals(LEGACY_STRUCTURE, notification.structureID());
			assertEquals(2, notification.attempts());
		}

		assertTrue(migratedDueDates.stream().allMatch(LEGACY_DUE::equals));
	}

	@Test
	void deactivationRecordsBothRecipientsAndTheActualActorInTheBusinessTransaction() {
		UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), structure = UUID.randomUUID();
		transaction
				.executeWithoutResult(status -> notificationStore.enqueueAccountDeactivation(actor, target, structure));
		var notifications = notifications();
		assertEquals(2, notifications.size());

		for (Notification notification : notifications) {
			assertEquals(actor, notification.actorUserID());
			assertEquals(target, notification.targetUserID());
			assertEquals(structure, notification.structureID());
			assertEquals(notification.kind() == Kind.ACCOUNT_DEACTIVATION ? actor : target,
					notification.recipientUserID());
		}

	}

	@Test
	void adminTransferSeparatesTheOperatorFromBothAdmins() {
		UUID actor = UUID.randomUUID(), oldAdmin = UUID.randomUUID(), newAdmin = UUID.randomUUID(),
				structure = UUID.randomUUID();
		transaction.executeWithoutResult(
				status -> notificationStore.enqueueAdminTransfer(actor, oldAdmin, newAdmin, structure));
		assertEquals(2, notifications().size());

		for (Notification notification : notifications()) {
			assertEquals(actor, notification.actorUserID());
			assertEquals(notification.kind() == Kind.ADMIN_REMOVED ? oldAdmin : newAdmin, notification.targetUserID());
			assertEquals(notification.targetUserID(), notification.recipientUserID());
			assertEquals(structure, notification.structureID());
		}

	}

	@Test
	void mfaEnrollmentQueuesOnlyTheAccountHolderWithoutAStructure() {
		UUID userID = UUID.randomUUID();
		transaction.executeWithoutResult(status -> notificationStore.enqueueMfaEnrollment(userID));
		Notification notification = notifications().getFirst();
		assertEquals(Kind.MFA_ENROLLED, notification.kind());
		assertEquals(userID, notification.actorUserID());
		assertEquals(userID, notification.targetUserID());
		assertEquals(userID, notification.recipientUserID());
		assertNull(notification.structureID());
	}

	@Test
	void enqueuingOutsideABusinessTransactionIsRejected() {
		assertThrows(IllegalTransactionStateException.class,
				() -> notificationStore.enqueueMfaEnrollment(UUID.randomUUID()));
		assertTrue(notifications().isEmpty());
	}

	@Test
	void aFailureOnTheSecondDeactivationInsertRollsBackTheFirstNotification() {
		jdbcTemplate.execute(
				"ALTER TABLE account_deactivation_notifications ADD CONSTRAINT fictitious_reject_target CHECK (kind <> 'ACCOUNT_DEACTIVATION_TARGET')");

		try {
			assertThrows(DataIntegrityViolationException.class,
					() -> transaction.executeWithoutResult(status -> notificationStore
							.enqueueAccountDeactivation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
			assertTrue(notifications().isEmpty());
		} finally {
			jdbcTemplate
					.execute("ALTER TABLE account_deactivation_notifications DROP CONSTRAINT fictitious_reject_target");
		}

	}

	@Test
	void businessRollbackRemovesTheMfaNotification() {
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			notificationStore.enqueueMfaEnrollment(UUID.randomUUID());
			throw new IllegalStateException("Échec métier fictif.");
		}));
		assertTrue(notifications().isEmpty());
	}

	@Test
	void databaseRejectsMfaWithoutMatchingIdentitiesAndNonMfaWithoutAStructure() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
				INSERT INTO account_deactivation_notifications
				    (notification_id, actor_user_id, target_id, recipient_user_id, kind)
				VALUES (?, ?, ?, ?, 'MFA_ENROLLED')
				""", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
				INSERT INTO account_deactivation_notifications (notification_id, target_id, recipient_user_id, kind)
				VALUES (?, ?, ?, 'ACCOUNT_DEACTIVATION_TARGET')
				""", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
	}

	@Test
	void concurrentWorkersSkipAnAlreadyLockedNotification() throws Exception {
		transaction.executeWithoutResult(status -> {
			notificationStore.enqueueMfaEnrollment(UUID.randomUUID());
			notificationStore.enqueueMfaEnrollment(UUID.randomUUID());
		});

		try (var workers = Executors.newSingleThreadExecutor()) {
			transaction.executeWithoutResult(status -> {
				UUID first = notificationStore.lockNext().orElseThrow().notificationID();

				try {
					UUID second = workers
							.submit(() -> transaction
									.execute(other -> notificationStore.lockNext().orElseThrow().notificationID()))
							.get(5, TimeUnit.SECONDS);
					assertFalse(first.equals(second));
				} catch (Exception failure) {
					throw new AssertionError(failure);
				}

			});
		}

	}

	@Test
	void reservationAndRetryPreserveTheAttemptCountAndPostponeAvailability() {
		transaction.executeWithoutResult(status -> notificationStore.enqueueMfaEnrollment(UUID.randomUUID()));
		UUID id = transaction.execute(status -> {
			Notification notification = notificationStore.lockNext().orElseThrow();
			notificationStore.reserve(notification.notificationID());
			return notification.notificationID();
		});
		assertTrue(transaction.execute(status -> notificationStore.lockNext()).isEmpty());
		assertEquals(1, notifications().getFirst().attempts());
		notificationStore.retry(id);
		assertEquals(1, notifications().getFirst().attempts());
		assertTrue(transaction.execute(status -> notificationStore.lockNext()).isEmpty());
		notificationStore.remove(id);
		assertTrue(notifications().isEmpty());
	}

	@Test
	void aHistoricalNotificationWithUnknownActorStillReachesItsOriginalRecipientOutsideATransaction() {
		Notification legacy = migratedNotifications.getFirst();
		jdbcTemplate.update("""
				INSERT INTO account_deactivation_notifications
				    (notification_id, actor_user_id, target_id, recipient_user_id, structure_id, kind)
				VALUES (?, NULL, ?, ?, ?, ?)
				""", legacy.notificationID(), legacy.targetUserID(), legacy.recipientUserID(), legacy.structureID(),
				legacy.kind().name());
		AccountStore accountStore = mock(AccountStore.class);
		when(accountStore.findById(LEGACY_RECIPIENT)).thenReturn(Optional.of(UserAccount.pending(LEGACY_RECIPIENT,
				"Fictitious", "Recipient", "fictitious-history@example.invalid", "+12025550100")));
		JavaMailSender mailSender = mock(JavaMailSender.class);
		doAnswer(call -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			assertEquals("fictitious-history@example.invalid", ((SimpleMailMessage) call.getArgument(0)).getTo()[0]);
			return null;
		}).when(mailSender).send(any(SimpleMailMessage.class));
		delivery(accountStore, mailSender).deliverNext();
		verify(mailSender).send(any(SimpleMailMessage.class));
		assertTrue(notifications().isEmpty());
	}

	@Test
	void smtpFailuresAreRetriedThreeTimesThenAbandonedWithoutHoldingATransactionDuringSmtp() {
		UUID userID = UUID.randomUUID();
		transaction.executeWithoutResult(status -> notificationStore.enqueueMfaEnrollment(userID));
		AccountStore accountStore = mock(AccountStore.class);
		when(accountStore.findById(userID)).thenReturn(Optional.of(UserAccount.pending(userID, "Fictitious", "MFA",
				"fictitious-mfa@example.invalid", "+12025550100")));
		JavaMailSender mailSender = mock(JavaMailSender.class);
		doAnswer(call -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			throw new MailSendException("Panne SMTP fictive.");
		}).when(mailSender).send(any(SimpleMailMessage.class));
		var delivery = delivery(accountStore, mailSender);

		for (int attempt = 1; attempt <= 3; attempt++) {
			delivery.deliverNext();
			assertEquals(attempt, notifications().getFirst().attempts());
			assertTrue(transaction.execute(status -> notificationStore.lockNext()).isEmpty());
			jdbcTemplate.update(
					"UPDATE account_deactivation_notifications SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
		}

		delivery.deliverNext();
		verify(mailSender, org.mockito.Mockito.times(3)).send(any(SimpleMailMessage.class));
		assertTrue(notifications().isEmpty());
	}

	private static AccountNotificationDelivery delivery(AccountStore accountStore, JavaMailSender mailSender) {
		MailDeliveryExecutor executor = mock(MailDeliveryExecutor.class);
		doAnswer(call -> {
			((Runnable) call.getArgument(0)).run();
			return true;
		}).when(executor).trySubmit(any());
		return new AccountNotificationDelivery(notificationStore, accountStore, mailSender,
				"fictitious@example.invalid",
				mock(AuditService.class), transactionManager, executor);
	}

	private static List<Notification> notifications() {
		return jdbcTemplate.query("SELECT * FROM account_deactivation_notifications ORDER BY kind",
				(row, number) -> new Notification(row.getObject("notification_id", UUID.class),
						row.getObject("actor_user_id", UUID.class),
						row.getObject("target_id", UUID.class), row.getObject("recipient_user_id", UUID.class),
						row.getObject("structure_id", UUID.class), Kind.valueOf(row.getString("kind")),
						row.getInt("attempts")));
	}
}
