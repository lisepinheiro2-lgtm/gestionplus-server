package fr.gestionplus.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SessionIDHasher {

	private SessionIDHasher() {
	}

	public static boolean isValidHash(String sessionIDHash) {
		return sessionIDHash != null && sessionIDHash.matches("[0-9a-f]{64}");
	}

	public static String hash(String sessionID) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(sessionID.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Impossible de générer l'empreinte de session.", e);
		}

	}
}
