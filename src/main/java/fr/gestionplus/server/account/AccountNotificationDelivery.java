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

import fr.gestionplus.server.account.AccountNotificationStore.Notification;
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
public class AccountNotificationDelivery {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountNotificationDelivery.class);
	private static final int MAX_ATTEMPTS = 3;

	private final AccountNotificationStore notifications;
	private final AccountStore accountStore;
	private final JavaMailSender mailSender;
	private final String from;
	private final AuditService audit;
	private final TransactionTemplate transaction;
	private final MailDeliveryExecutor executor;

	public AccountNotificationDelivery(AccountNotificationStore notifications,
			AccountStore accountStore, JavaMailSender mailSender,
			@Value("${gestionplus.notifications.from}") String from, AuditService audit,
			PlatformTransactionManager transactionManager, MailDeliveryExecutor executor) {
		this.notifications = notifications;
		this.accountStore = accountStore;
		this.mailSender = mailSender;
		this.from = from;
		this.audit = audit;
		transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.executor = executor;
	}

	@Scheduled(fixedDelay = 5000)
	public void deliverNext() {
		executor.trySubmit(this::deliverDue);
	}

	private void deliverDue() {

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
					Set.of(new AuditTarget(AuditTargetType.ACCOUNT, notification.recipientUserID())),
					AuditMetadata.because(AuditReason.OPERATION_FAILED));
		});
		LOGGER.warn("Notification {} abandonnée après {} tentatives", notification.notificationID(),
				notification.attempts());
	}

	private void deliver(Notification notification) {

		String recipient = accountStore.findById(notification.recipientUserID()).map(UserAccount::getEmail)
				.orElse(null);

		if (recipient == null || recipient.isBlank()) {
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
					Le retrait du dernier accès a entraîné la désactivation du compte concerné.

					Référence du compte : %s
					Référence de la structure : %s
					""".formatted(notification.targetUserID(), notification.structureID()));
			break;

		case ACCOUNT_DEACTIVATION_TARGET:
			message.setSubject("Gestion+ — Désactivation de votre compte");
			message.setText("""
					Votre compte Gestion+ a été désactivé après le retrait de son dernier accès.
					L’accès à ce compte n’est plus disponible.

					Si vous ne reconnaissez pas cette opération, contactez votre administrateur
					par votre canal habituel. Ne communiquez jamais votre mot de passe ou vos codes.
					""");
			break;

		case ADMIN_REMOVED:
			message.setSubject("Gestion+ — Fin de votre fonction ADMIN");
			message.setText(
					"Votre fonction ADMIN a été transférée pour la structure " + notification.structureID() + ".");
			break;

		case MFA_ENROLLED:
			message.setSubject("Gestion+ — Double authentification activée");
			message.setText("""
					La double authentification de votre compte Gestion+ vient d’être activée.

					Si vous n’êtes pas à l’origine de cette opération, contactez sans délai
					votre administrateur par votre canal habituel.
					Ne communiquez jamais votre mot de passe, votre clé d’authentification
					ou vos codes de validation et de récupération.
					""");
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
