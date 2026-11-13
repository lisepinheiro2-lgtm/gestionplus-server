package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.Set;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountDeactivationNotifications.Notification;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.UserAccount;

@Service
@EnableScheduling
@ConditionalOnProperty(name = { "spring.mail.host", "gestionplus.notifications.from" })
public class AccountDeactivationNotificationDelivery {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountDeactivationNotificationDelivery.class);
	private static final int MAX_ATTEMPTS = 3;

	private final AccountDeactivationNotifications notifications;
	private final AccountStore accountStore;
	private final JavaMailSender mailSender;
	private final String from;
	private final AuditService audit;
	private final TransactionTemplate transaction;

	public AccountDeactivationNotificationDelivery(AccountDeactivationNotifications notifications,
			AccountStore accountStore, JavaMailSender mailSender,
			@Value("${gestionplus.notifications.from}") String from, AuditService audit,
			PlatformTransactionManager transactionManager) {
		this.notifications = notifications;
		this.accountStore = accountStore;
		this.mailSender = mailSender;
		this.from = from;
		this.audit = audit;
		transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Scheduled(fixedDelay = 5000)
	public void deliverNext() {

		try {
			Notification notification = reserveNext();

			if (notification == null) {
				return;
			}

			if (notification.attempts() >= MAX_ATTEMPTS) {
				abandon(notification);
				return;
			}

			deliver(notification);

		} catch (RuntimeException failure) {
			LOGGER.warn("Livraison des notifications impossible ({})", failure.getClass().getSimpleName());
		}

	}

	private Notification reserveNext() {
		return transaction.execute(status -> {
			Optional<Notification> locked = notifications.lockNext();

			if (locked.isEmpty()) {
				return null;
			}

			notifications.reserve(locked.get().notificationID());
			return locked.get();
		});
	}

	private void abandon(Notification notification) {
		transaction.executeWithoutResult(status -> {
			notifications.remove(notification.notificationID());
			audit.recordForActor(AuditAction.NOTIFICATION_ABANDON, AuditResult.FAILURE, null, null,
					Set.of(new AuditTarget(AuditTargetType.ACCOUNT, notification.actorUserID())),
					AuditMetadata.because(AuditReason.OPERATION_FAILED));
		});
		LOGGER.warn("Notification {} abandonnée après {} tentatives", notification.notificationID(),
				notification.attempts());
	}

	private void deliver(Notification notification) {

		String recipient = accountStore.findById(notification.actorUserID()).map(UserAccount::getEmail).orElse(null);

		if (recipient == null) {
			LOGGER.warn("Destinataire introuvable pour la notification {}", notification.notificationID());
			transaction.executeWithoutResult(status -> notifications.retry(notification.notificationID()));
			return;
		}

		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipient);

		switch (notification.kind()) {

		case ACCOUNT_DEACTIVATION:
			message.setSubject("Gestion+ — Désactivation après révocation d'accès");
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
			transaction.executeWithoutResult(status -> notifications.retry(notification.notificationID()));
			return;
		}

		transaction.executeWithoutResult(status -> notifications.remove(notification.notificationID()));
	}
}
