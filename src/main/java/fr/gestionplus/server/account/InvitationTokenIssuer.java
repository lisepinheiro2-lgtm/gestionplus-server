package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvitationTokenIssuer {

	private final SecretKeySpec key;

	public InvitationTokenIssuer(@Value("${gestionplus.activation.delivery-key:}") String configuredKey) {
		byte[] decoded = configuredKey.isBlank() ? null : Base64.getDecoder().decode(configuredKey);

		if (decoded != null && decoded.length != 32) {
			throw new IllegalArgumentException("La clé d'envoi des invitations doit contenir 256 bits.");
		}

		key = decoded == null ? null : new SecretKeySpec(decoded, "HmacSHA256");

		if (decoded != null) {
			Arrays.fill(decoded, (byte) 0);
		}

	}

	String token(UUID requestID, UUID invitationID) {

		if (key == null) {
			throw new IllegalStateException("La clé d'envoi des invitations est obligatoire.");
		}

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			byte[] input = ("gestionplus-invitation-v1:" + requestID + ":" + invitationID)
					.getBytes(StandardCharsets.US_ASCII);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input));
		} catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Création du jeton d'invitation impossible.", failure);
		}

	}

	static String digest(String token) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 indisponible.", failure);
		}

	}
}
