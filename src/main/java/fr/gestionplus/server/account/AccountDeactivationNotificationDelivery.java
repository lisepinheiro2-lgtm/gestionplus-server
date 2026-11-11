package fr.gestionplus.server.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountDeactivationNotifications.Notification;
import fr.gestionplus.server.auth.UserAccount;

@Service
@EnableScheduling
@ConditionalOnProperty(name = { "spring.mail.host", "gestionplus.notifications.from" })
public class AccountDeactivationNotificationDelivery {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountDeactivationNotificationDelivery.class);

	private final AccountDeactivationNotifications notifications;
	private final AccountStore accountStore;
	private final JavaMailSender mailSender;
	private final String from;

	public AccountDeactivationNotificationDelivery(AccountDeactivationNotifications notifications,
			AccountStore accountStore, JavaMailSender mailSender,
			@Value("${gestionplus.notifications.from}") String from) {
		this.notifications = notifications;
		this.accountStore = accountStore;
		this.mailSender = mailSender;
		this.from = from;
	}

	@Scheduled(fixedDelay = 5000)
	@Transactional
	public void deliverNext() {
		notifications.lockNext().ifPresent(this::deliver);
	}

	private void deliver(Notification notification) {

		String recipient = accountStore.findById(notification.actorUserID()).map(UserAccount::getEmail).orElse(null);

		if (recipient == null) {
			LOGGER.warn("Destinataire introuvable pour la notification {}", notification.notificationID());
			notifications.retry(notification.notificationID());
			return;
		}

		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipient);

		switch (notification.kind()) {

		case ACCOUNT_DEACTIVATION:
			message.setSubject("Gestion+ — Désactivation après révocation d’accès");
			message.setText("""
					Votre révocation du dernier rattachement préparé a désactivé le compte en attente.

					Référence du compte : %s
					Référence de la structure : %s
					""".formatted(notification.targetUserID(), notification.structureID()));
			break;

		case ADMIN_REMOVED:
			message.setSubject("Gestion+ — Fin de votre fonction ADMIN");
			message.setText(
					"Votre fonction ADMIN a été transférée pour la structure " + notification.structureID() + ".");
			break;

		case ADMIN_GRANTED:
			message.setSubject("Gestion+ — Nouvelle fonction ADMIN");
			message.setText("Votre fonction ADMIN a été attribuée pour la structure " + notification.structureID()
					+ ". Reconnectez-vous pour consulter vos droits.");
			break;
		}

		try {
			mailSender.send(message);
		} catch (MailException failure) {
			LOGGER.warn("Échec de la notification {} ({})", notification.notificationID(),
					failure.getClass().getSimpleName());
			notifications.retry(notification.notificationID());
			return;
		}

		notifications.remove(notification.notificationID());
	}
}
