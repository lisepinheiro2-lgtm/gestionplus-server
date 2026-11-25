package fr.gestionplus.server.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.gestionplus.server.account.ActivationMessageDeliveryException;
import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;
import fr.gestionplus.server.account.SmsMessageSender;
import tools.jackson.databind.json.JsonMapper;

public final class FreeMobileActivationMessageSender implements SmsMessageSender, AutoCloseable {

	private static final URI ENDPOINT = URI.create("https://smsapi.free-mobile.fr/sendmsg");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Logger LOGGER = LoggerFactory.getLogger(FreeMobileActivationMessageSender.class);
	private final HttpClient client;
	private final String accountId;
	private final String apiKey;
	private final String recipient;

	FreeMobileActivationMessageSender(HttpClient client, String accountId, String apiKey, String recipient) {
		this.client = Objects.requireNonNull(client);

		if (accountId == null || !accountId.matches("[0-9]{8}") || apiKey == null || apiKey.isBlank()
				|| apiKey.length() > 256 || apiKey.chars().anyMatch(value -> Character.isISOControl(value)
						|| Character.isWhitespace(value))
				|| recipient == null || !recipient.matches("\\+[1-9][0-9]{1,14}")) {
			throw new IllegalArgumentException("La configuration de l’expéditeur SMS Free de test est invalide.");
		}

		if (client.followRedirects() != HttpClient.Redirect.NEVER) {
			throw new IllegalArgumentException("L’expéditeur SMS Free de test doit refuser les redirections HTTP.");
		}

		this.accountId = accountId;
		this.apiKey = apiKey;
		this.recipient = recipient;
	}

	@Override
	public void send(Purpose purpose, String phoneNumber, String code) {

		if (!recipient.equals(phoneNumber)) {
			throw new ActivationMessageDeliveryException(purpose);
		}

		if (code == null || !code.matches("[0-9]{6}")) {
			throw new IllegalArgumentException("Le code d’activation doit contenir exactement six chiffres.");
		}

		String message = SmsMessageSender.formatMessage(purpose, code);
		byte[] body = JSON.writeValueAsBytes(Map.of("user", accountId, "pass", apiKey, "msg", message));
		HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("User-Agent", "GestionPlus-Server")
				.timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

		try {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

			if (response.statusCode() != 200) {
				LOGGER.warn("Échec de l’envoi SMS Free de test (HTTP {}).", response.statusCode());
				throw new ActivationMessageDeliveryException(purpose);
			}

		} catch (IOException failure) {
			LOGGER.warn("Échec réseau de l’envoi SMS Free de test ({}).", failure.getClass().getSimpleName());
			throw new ActivationMessageDeliveryException(purpose);
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new ActivationMessageDeliveryException(purpose);
		}

	}

	@Override
	public void close() {
		client.close();
	}
}
