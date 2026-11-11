package fr.gestionplus.server.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MfaSecretCipher {

	private static final byte VERSION = 1;
	private static final int NONCE_BYTES = 12;
	static final int SECRET_BYTES = 20;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	MfaSecretCipher(@Value("${gestionplus.mfa.key:}") String configuredKey) {
		byte[] decoded = configuredKey.isBlank() ? null : Base64.getDecoder().decode(configuredKey);

		if (decoded != null && decoded.length != 32) {
			throw new IllegalArgumentException("La clé MFA doit contenir 256 bits.");
		}

		key = decoded == null ? null : new SecretKeySpec(decoded, "AES");

		if (decoded != null) {
			Arrays.fill(decoded, (byte) 0);
		}

	}

	boolean available() {
		return key != null;
	}

	byte[] encrypt(UUID userID, UUID enrollmentID, byte[] secret) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, userID, enrollmentID, nonce, secret);
		return ByteBuffer.allocate(1 + nonce.length + ciphertext.length).put(VERSION).put(nonce).put(ciphertext)
				.array();
	}

	byte[] decrypt(UUID userID, UUID enrollmentID, byte[] encrypted) {

		if (encrypted.length != 1 + NONCE_BYTES + SECRET_BYTES + 16 || encrypted[0] != VERSION) {
			throw new IllegalStateException("Facteur MFA invalide.");
		}

		byte[] nonce = Arrays.copyOfRange(encrypted, 1, 1 + NONCE_BYTES);
		byte[] ciphertext = Arrays.copyOfRange(encrypted, 1 + NONCE_BYTES, encrypted.length);
		return transform(Cipher.DECRYPT_MODE, userID, enrollmentID, nonce, ciphertext);
	}

	private byte[] transform(int mode, UUID userID, UUID enrollmentID, byte[] nonce, byte[] input) {

		if (!available()) {
			throw new IllegalStateException("Clé MFA indisponible.");
		}

		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(mode, key, new GCMParameterSpec(128, nonce));
			cipher.updateAAD(("gestionplus-mfa-v1:" + userID + ":" + (enrollmentID == null ? "factor" : enrollmentID))
					.getBytes(StandardCharsets.US_ASCII));
			return cipher.doFinal(input);
		} catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Facteur MFA illisible.", failure);
		}

	}
}
