package fr.gestionplus.server.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

import fr.gestionplus.server.auth.CompromisedPasswordChecker;

public class PwnedPasswordsChecker implements CompromisedPasswordChecker {

	private final HttpClient client;

	public PwnedPasswordsChecker(HttpClient client) {
		this.client = Objects.requireNonNull(client);
	}

	@Override
	public boolean isCompromised(String rawPassword) {

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] bytesPasseword = rawPassword.getBytes(StandardCharsets.UTF_8);
			byte[] bytes = digest.digest(bytesPasseword);
			String hash = HexFormat.of().formatHex(bytes);
			String prefix = hash.substring(0, 5);
			String suffix = hash.substring(5);

			URI uri = URI.create("https://api.pwnedpasswords.com/range/" + prefix);

			HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
			builder.header("Add-Padding", "true");
			builder.header("User-Agent", "GestionPlus-Server");
			builder.timeout(Duration.ofSeconds(5));

			HttpRequest request = builder.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200)
				throw new IllegalStateException(
						"Échec de la vérification du mot de passe. Code HTTP : " + response.statusCode());

			String responseBody = response.body();

			if (responseBody == null || responseBody.isBlank())
				throw new IllegalStateException("Réponse invalide du service de vérification des mots de passe.");

			String[] lines = responseBody.split("\\R");

			for (String line : lines) {
				String[] parts = line.split(":");

				if (parts.length != 2)
					throw new IllegalStateException("Réponse invalide du service de vérification des mots de passe.");

				if (parts[0].length() != 35)
					throw new IllegalStateException("Réponse invalide du service de vérification des mots de passe.");

				for (int i = 0; i < parts[0].length(); i++) {
					char currentChar = parts[0].charAt(i);

					if (!(currentChar >= '0' && currentChar <= '9' || currentChar >= 'A' && currentChar <= 'F'
							|| currentChar >= 'a' && currentChar <= 'f'))
						throw new IllegalStateException(
								"Réponse invalide du service de vérification des mots de passe.");
				}

				if (parts[0].equalsIgnoreCase(suffix)) {
					int count = Integer.parseInt(parts[1]);

					if (count > 0)
						return true;

					if (count == 0)
						continue;

					if (count < 0)
						throw new IllegalStateException(
								"Réponse invalide du service de vérification des mots de passe.");
				}
			}

			return false;

		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Impossible de calculer l'empreinte SHA-1.", e);
		} catch (IOException e) {
			throw new IllegalStateException(
					"Le service de vérification de mot de passe n'est pas disponible, veuillez réessayer plus tard.",
					e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interruption pendant la vérification du mot de passe.", e);
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Réponse invalide du service de vérification des mots de passe.", e);
		}
	}
}
