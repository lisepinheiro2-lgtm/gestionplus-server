package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import fr.gestionplus.server.account.AccountDeactivationNotifications.Kind;
import fr.gestionplus.server.account.AccountDeactivationNotifications.Notification;
import fr.gestionplus.server.auth.UserAccount;

class AccountDeactivationNotificationDeliveryTest {

	private final AccountDeactivationNotifications notifications = mock(AccountDeactivationNotifications.class);
	private final AccountStore accountStore = mock(AccountStore.class);
	private final JavaMailSender mailSender = mock(JavaMailSender.class);
	private final AccountDeactivationNotificationDelivery delivery = new AccountDeactivationNotificationDelivery(
			notifications, accountStore, mailSender, "gestionplus@example.invalid");

	@Test
	void sendsToTheActorAndRemovesTheDeliveredNotification() {
		Notification notification = prepareNotification();
		ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);

		delivery.deliverNext();

		verify(mailSender).send(message.capture());
		assertEquals("actor@example.invalid", message.getValue().getTo()[0]);
		assertTrue(message.getValue().getText().contains(notification.targetUserID().toString()));
		assertTrue(message.getValue().getText().contains(notification.structureID().toString()));
		verify(notifications).remove(notification.notificationID());
		verify(notifications, never()).retry(any());
	}

	@Test
	void retainsTheNotificationAndSchedulesARetryOnMailFailure() {
		Notification notification = prepareNotification();
		doThrow(new MailSendException("Panne simulée")).when(mailSender).send(any(SimpleMailMessage.class));

		delivery.deliverNext();

		verify(notifications).retry(notification.notificationID());
		verify(notifications, never()).remove(any());
		verify(accountStore, never()).disableAccount(any());
	}

	@ParameterizedTest
	@CsvSource({ "ADMIN_REMOVED, Fin de votre fonction ADMIN", "ADMIN_GRANTED, Nouvelle fonction ADMIN" })
	void sendsTheAppropriateTransferNotification(Kind kind, String subject) {
		Notification base = prepareNotification();
		Notification transfer = new Notification(base.notificationID(), base.actorUserID(), base.targetUserID(),
				base.structureID(), kind);
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

	private Notification prepareNotification() {
		Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID());
		UserAccount actor = UserAccount.pending(notification.actorUserID(), "Exemple", "Test", "actor@example.invalid",
				"0600000000");
		when(notifications.lockNext()).thenReturn(Optional.of(notification));
		when(accountStore.findById(notification.actorUserID())).thenReturn(Optional.of(actor));
		return notification;
	}
}
