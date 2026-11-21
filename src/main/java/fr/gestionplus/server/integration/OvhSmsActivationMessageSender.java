package fr.gestionplus.server.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import fr.gestionplus.server.account.ActivationMessageDeliveryException;
import fr.gestionplus.server.account.ActivationMessageSender;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class OvhSmsActivationMessageSender implements ActivationMessageSender, AutoCloseable {

	private static final URI TIME_ENDPOINT = URI.create("https://api.eu.ovhcloud.com/1.0/auth/time");
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private final HttpClient client;
	private final String applicationKey;
	private final String applicationSecret;
	private final String consumerKey;
	private final String sender;
	private final URI jobsEndpoint;

	OvhSmsActivationMessageSender(HttpClient client, String applicationKey, String applicationSecret,
			String consumerKey, String serviceName, String sender) {
		this.client = Objects.requireNonNull(client);
		if (!validKey(applicationKey) || !validKey(applicationSecret) || !validKey(consumerKey)
				|| serviceName == null || !serviceName.matches("sms-[A-Za-z0-9-]{1,64}")
				|| sender == null || !sender.matches("[A-Za-z0-9]{1,11}")) {
			throw new IllegalArgumentException("La configuration de l’expéditeur SMS OVHcloud est invalide.");
		}
		if (client.followRedirects() != HttpClient.Redirect.NEVER) {
			throw new IllegalArgumentException("L’expéditeur SMS OVHcloud doit refuser les redirections HTTP.");
		}
		this.applicationKey = applicationKey;
		this.applicationSecret = applicationSecret;
		this.consumerKey = consumerKey;
		this.sender = sender;
		jobsEndpoint = URI.create("https://api.eu.ovhcloud.com/1.0/sms/" + serviceName + "/jobs");
	}

	@Override
	public void send(String phoneNumber, String code) {
		if (phoneNumber == null || !phoneNumber.matches("\\+[1-9][0-9]{1,14}")
				|| code == null || !code.matches("[0-9]{6}")) {
			throw new IllegalArgumentException("Le numéro ou le code d’activation est invalide.");
		}
		try {
			HttpResponse<String> time = exchange(HttpRequest.newBuilder(TIME_ENDPOINT)
					.timeout(Duration.ofSeconds(5)).GET().build());
			String timestamp = time.body().strip();
			if (time.statusCode() != 200 || !timestamp.matches("[0-9]{10,12}")) {
				throw new ActivationMessageDeliveryException();
			}
			String body = JSON.writeValueAsString(Map.of("sender", sender, "receivers", List.of(phoneNumber),
					"message", "Gestion+ - Code d'activation : " + code + ". Ne le communiquez a personne.",
					"noStopClause", true, "validityPeriod", 5, "priority", "high"));
			String material = applicationSecret + "+" + consumerKey + "+POST+" + jobsEndpoint + "+" + body
					+ "+" + timestamp;
			String signature = "$1$" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
					.digest(material.getBytes(StandardCharsets.UTF_8)));
			HttpRequest request = HttpRequest.newBuilder(jobsEndpoint).timeout(Duration.ofSeconds(5))
					.header("Content-Type", "application/json; charset=UTF-8")
					.header("X-Ovh-Application", applicationKey).header("X-Ovh-Consumer", consumerKey)
					.header("X-Ovh-Timestamp", timestamp).header("X-Ovh-Signature", signature)
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
			HttpResponse<String> response = exchange(request);
			if (response.statusCode() != 200 || !accepted(JSON.readTree(response.body()), phoneNumber)) {
				throw new ActivationMessageDeliveryException();
			}
		} catch (NoSuchAlgorithmException | RuntimeException failure) {
			throw new ActivationMessageDeliveryException();
		}
	}

	private HttpResponse<String> exchange(HttpRequest request) {
		var pending = client.sendAsync(request,
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 16384));
		try {
			return pending.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException failure) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			throw new ActivationMessageDeliveryException();
		} catch (ExecutionException | TimeoutException | RuntimeException failure) {
			pending.cancel(true);
			throw new ActivationMessageDeliveryException();
		}
	}

	private boolean accepted(JsonNode report, String phoneNumber) {
		if (report == null || !report.isObject()) {
			return false;
		}
		JsonNode valid = report.get("validReceivers");
		JsonNode invalid = report.get("invalidReceivers");
		JsonNode ids = report.get("ids");
		return valid != null && valid.isArray() && valid.size() == 1 && valid.get(0).isTextual()
				&& phoneNumber.equals(valid.get(0).asString()) && invalid != null && invalid.isArray()
				&& invalid.isEmpty() && ids != null && ids.isArray() && ids.size() == 1
				&& ids.get(0).isIntegralNumber() && ids.get(0).canConvertToLong() && ids.get(0).longValue() > 0;
	}

	private static boolean validKey(String key) {
		return key != null && key.matches("[A-Za-z0-9]{1,256}");
	}

	@Override
	public void close() {
		client.shutdownNow();
	}
}
