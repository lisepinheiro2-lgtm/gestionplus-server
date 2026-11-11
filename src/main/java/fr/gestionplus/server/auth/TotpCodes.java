package fr.gestionplus.server.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TotpCodes {

	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	private TotpCodes() {
	}

	static OptionalLong matchingStep(byte[] secret, String code, Instant now, long lastAcceptedStep) {

		if (code == null || !code.matches("[0-9]{6}")) {
			return OptionalLong.empty();
		}

		long current = Math.floorDiv(now.getEpochSecond(), 30);
		byte[] submitted = code.getBytes(StandardCharsets.US_ASCII);

		for (long step = current; step >= current - 1 && step > lastAcceptedStep; step--) {

			if (MessageDigest.isEqual(submitted, atStep(secret, step).getBytes(StandardCharsets.US_ASCII))) {
				return OptionalLong.of(step);
			}

		}

		return OptionalLong.empty();
	}

	static String atStep(byte[] secret, long step) {

		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
			int offset = digest[digest.length - 1] & 0x0f;
			int truncated = ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt() & 0x7fff_ffff;
			return String.format(Locale.ROOT, "%06d", truncated % 1_000_000);
		} catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Calcul TOTP indisponible.", failure);
		}

	}

	static String setupKey(byte[] secret) {
		StringBuilder result = new StringBuilder();
		int buffer = 0;
		int bits = 0;

		for (byte value : secret) {
			buffer = (buffer << 8) | (value & 0xff);
			bits += 8;

			while (bits >= 5) {
				bits -= 5;
				result.append(BASE32.charAt((buffer >> bits) & 31));
			}

		}

		if (bits != 0) {
			result.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}

		return result.toString();
	}
}
