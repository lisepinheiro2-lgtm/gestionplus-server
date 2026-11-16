package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ActivationCodeDigester {

	private final SecretKeySpec key;

	public ActivationCodeDigester(@Value("${gestionplus.activation.challenge-key:}") String configuredKey) {
		byte[] decoded = configuredKey.isBlank() ? null : Base64.getDecoder().decode(configuredKey);

		if (decoded != null && decoded.length != 32) {
			throw new IllegalArgumentException("La clé d'envoi du code doit contenir 256 bits.");
		}

		key = decoded == null ? null : new SecretKeySpec(decoded, "HmacSHA256");

		if (decoded != null) {
			Arrays.fill(decoded, (byte) 0);
		}

	}

	String digest(UUID challengeID, String code) {

		if (key == null) {
			throw new IllegalStateException("La clé d'envoi du code est obligatoire.");
		}

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			byte[] input = ("gestionplus-activation-challenge-v1:" + challengeID + ":" + code)
					.getBytes(StandardCharsets.US_ASCII);

			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(input));

		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Calcul de l'empreinte du défi impossible.", e);
		}

	}
}
