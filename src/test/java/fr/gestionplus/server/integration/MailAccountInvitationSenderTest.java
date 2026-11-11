package fr.gestionplus.server.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailAccountInvitationSenderTest {

	private final JavaMailSender transport = mock(JavaMailSender.class);
	private final UUID invitationID = UUID.randomUUID();
	private final String token = "t".repeat(43);

	@Test
	void sendsOnlyToTheValidatedAddressWithTheTokenInTheHttpsFragment() {
		MailAccountInvitationSender sender = sender("noreply@example.invalid",
				"https://activation.example.invalid/start", true);

		assertTrue(sender.send("recipient@example.invalid", invitationID, token));

		ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(transport).send(message.capture());
		assertEquals("noreply@example.invalid", message.getValue().getFrom());
		assertEquals("recipient@example.invalid", message.getValue().getTo()[0]);
		assertTrue(message.getValue().getText().contains(
				"https://activation.example.invalid/start#invitation=" + invitationID + "&token=" + token));
		assertFalse(message.getValue().getText().contains("?token="));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://activation.example.invalid", "//activation.example.invalid",
			"https://user:password@activation.example.invalid", "https://activation.example.invalid?token=private",
			"https://activation.example.invalid#private", "https://", "not a URI" })
	void refusesUnsafeActivationLinks(String link) {
		assertFalse(sender("noreply@example.invalid", link, true)
				.send("recipient@example.invalid", invitationID, token));
		verifyNoInteractions(transport);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "bad address", "one@example.invalid,two@example.invalid",
			"Display <recipient@example.invalid>", "group:one@example.invalid;",
			"recipient@example.invalid\r\nBcc: other@example.invalid" })
	void refusesInvalidRecipientsWithoutCallingTheTransport(String recipient) {
		assertFalse(sender("noreply@example.invalid", "https://activation.example.invalid", true)
				.send(recipient, invitationID, token));
		verifyNoInteractions(transport);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "short", "a\r\nheader", "a&token=other", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
	void refusesMissingOrMalformedTokens(String supplied) {
		assertFalse(sender("noreply@example.invalid", "https://activation.example.invalid", true)
				.send("recipient@example.invalid", invitationID, supplied));
		verifyNoInteractions(transport);
	}

	@Test
	void refusesMissingConfigurationAndMissingInvitationIds() {
		assertFalse(sender("", "https://activation.example.invalid", true)
				.send("recipient@example.invalid", invitationID, token));
		assertFalse(sender("noreply@example.invalid", "", true)
				.send("recipient@example.invalid", invitationID, token));
		assertFalse(sender("noreply@example.invalid", "https://activation.example.invalid", false)
				.send("recipient@example.invalid", invitationID, token));
		assertFalse(sender("noreply@example.invalid", "https://activation.example.invalid", true)
				.send("recipient@example.invalid", null, token));
		verifyNoInteractions(transport);
	}

	@ParameterizedTest
	@ValueSource(strings = { "not an address", "group:one@example.invalid;" })
	void refusesInvalidOrGroupedSenders(String from) {
		assertFalse(sender(from, "https://activation.example.invalid", true)
				.send("recipient@example.invalid", invitationID, token));
		verifyNoInteractions(transport);
	}

	@Test
	void reportsTransportFailureWithoutPropagatingItsSecretContainingMessage() {
		doThrow(new MailSendException("password=private token=" + token))
				.when(transport).send(any(SimpleMailMessage.class));

		assertFalse(sender("noreply@example.invalid", "https://activation.example.invalid", true)
				.send("recipient@example.invalid", invitationID, token));
	}

	private MailAccountInvitationSender sender(String from, String link, boolean configured) {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();

		if (configured) {
			beans.addBean("mail", transport);
		}

		return new MailAccountInvitationSender(beans.getBeanProvider(JavaMailSender.class), from, link);
	}
}
