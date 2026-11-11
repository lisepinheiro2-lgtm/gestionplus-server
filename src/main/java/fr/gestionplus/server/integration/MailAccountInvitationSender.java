package fr.gestionplus.server.integration;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.account.AccountInvitationSender;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

@Component
public class MailAccountInvitationSender implements AccountInvitationSender {

	private final ObjectProvider<JavaMailSender> senders;
	private final String from;
	private final String activationLink;

	public MailAccountInvitationSender(ObjectProvider<JavaMailSender> senders,
			@Value("${gestionplus.notifications.from:}") String from,
			@Value("${gestionplus.activation.link:}") String activationLink) {
		this.senders = senders;
		this.from = from;
		this.activationLink = activationLink;
	}

	@Override
	public boolean send(String email, UUID invitationID, String token) {
		JavaMailSender sender = senders.getIfAvailable();

		if (sender == null || from.isBlank() || activationLink.isBlank()) {
			return false;
		}

		try {
			URI link = URI.create(activationLink);

			if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null || link.getUserInfo() != null
					|| link.getRawQuery() != null || link.getRawFragment() != null) {
				return false;
			}

			InternetAddress senderAddress = new InternetAddress(from, true);
			senderAddress.validate();
			InternetAddress recipient = new InternetAddress(email, true);
			recipient.validate();

			if (recipient.isGroup() || !email.equals(recipient.getAddress())) {
				return false;
			}

			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(senderAddress.getAddress());
			message.setTo(recipient.getAddress());
			message.setSubject("Gestion+ — Invitation à activer votre compte");
			message.setText("""
					Pour commencer l’activation de votre compte Gestion+, utilisez ce lien dans les sept jours.

					%s#invitation=%s&token=%s
					""".formatted(link, invitationID, token));
			sender.send(message);
			return true;
		} catch (MailException | AddressException | IllegalArgumentException failure) {
			return false;
		}

	}
}
