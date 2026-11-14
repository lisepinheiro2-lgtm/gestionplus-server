package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

import fr.gestionplus.server.account.AccountDeactivationNotifications.Kind;
import fr.gestionplus.server.account.AccountDeactivationNotifications.Notification;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.UserAccount;

class AccountDeactivationNotificationDeliveryTest {

	private final AccountDeactivationNotifications notifications = mock(AccountDeactivationNotifications.class);
	private final AccountStore accountStore = mock(AccountStore.class);
	private final JavaMailSender mailSender = mock(JavaMailSender.class);
	private final AuditService audit = mock(AuditService.class);
	private final MailDeliveryExecutor executor = synchronousExecutor();
	private final AccountDeactivationNotificationDelivery delivery = new AccountDeactivationNotificationDelivery(
			notifications, accountStore, mailSender, "gestionplus@example.invalid", audit,
			mock(PlatformTransactionManager.class), executor);

	@Test
	void sendsToTheActorAndRemovesTheDeliveredNotification() {
		Notification notification = prepareNotification(0);
		ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);

		delivery.deliverNext();

		InOrder order = inOrder(notifications, mailSender);
		order.verify(notifications).reserve(notification.notificationID());
		order.verify(mailSender).send(message.capture());
		assertEquals("actor@example.invalid", message.getValue().getTo()[0]);
		assertTrue(message.getValue().getText().contains(notification.targetUserID().toString()));
		assertTrue(message.getValue().getText().contains(notification.structureID().toString()));
		verify(notifications).remove(notification.notificationID());
		verify(notifications, never()).retry(any());
	}

	@Test
	void retainsTheNotificationAndSchedulesARetryOnMailFailure() {
		Notification notification = prepareNotification(0);
		doThrow(new MailSendException("Panne simulée")).when(mailSender).send(any(SimpleMailMessage.class));

		delivery.deliverNext();

		verify(notifications).reserve(notification.notificationID());
		verify(notifications).retry(notification.notificationID());
		verify(notifications, never()).remove(any());
		verify(accountStore, never()).disableAccount(any());
	}

	@ParameterizedTest
	@CsvSource({ "ADMIN_REMOVED, Fin de votre fonction ADMIN", "ADMIN_GRANTED, Nouvelle fonction ADMIN" })
	void sendsTheAppropriateTransferNotification(Kind kind, String subject) {
		Notification base = prepareNotification(0);
		Notification transfer = new Notification(base.notificationID(), base.actorUserID(), base.targetUserID(),
				base.structureID(), kind, 0);
		when(notifications.lockNext()).thenReturn(Optional.of(transfer));
		ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);

		delivery.deliverNext();

		verify(mailSender).send(message.capture());
		assertEquals("Gestion+ — " + subject, message.getValue().getSubject());
		assertTrue(message.getValue().getText().contains(transfer.structureID().toString()));
		verify(notifications).remove(transfer.notificationID());
	}

	@Test
	void makesNoMailCallWhenNothingIsDue() {
		when(notifications.lockNext()).thenReturn(Optional.empty());

		delivery.deliverNext();

		verifyNoInteractions(mailSender, accountStore);
	}

	@ParameterizedTest
	@CsvSource({ "0", "1", "2" })
	void sendsWhileTheAttemptCeilingIsNotReached(int attempts) {
		Notification notification = prepareNotification(attempts);

		delivery.deliverNext();

		verify(mailSender).send(any(SimpleMailMessage.class));
		verify(notifications).remove(notification.notificationID());
	}

	@Test
	void abandonsTheNotificationOnceTheAttemptCeilingIsReached() {
		Notification notification = prepareNotification(3);

		delivery.deliverNext();

		verifyNoInteractions(mailSender);
		verify(notifications).remove(notification.notificationID());
		verify(audit).recordForActor(AuditAction.NOTIFICATION_ABANDON, AuditResult.FAILURE, null, null,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, notification.actorUserID())),
				AuditMetadata.because(AuditReason.OPERATION_FAILED));
	}

	private Notification prepareNotification(int attempts) {
		Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), Kind.ACCOUNT_DEACTIVATION, attempts);
		UserAccount actor = UserAccount.pending(notification.actorUserID(), "Exemple", "Test", "actor@example.invalid",
				"0600000000");
		when(notifications.lockNext()).thenReturn(Optional.of(notification));
		when(accountStore.findById(notification.actorUserID())).thenReturn(Optional.of(actor));
		return notification;
	}

	private MailDeliveryExecutor synchronousExecutor() {
		MailDeliveryExecutor executor = mock(MailDeliveryExecutor.class);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return true;
		}).when(executor).trySubmit(any());
		return executor;
	}
}
