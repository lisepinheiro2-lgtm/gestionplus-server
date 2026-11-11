package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpCodesTest {

	private final byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	@ParameterizedTest
	@CsvSource({ "59,287082", "1111111109,081804", "1111111111,050471", "1234567890,005924" })
	void computesTheSixDigitTruncationOfRfc6238Vectors(long second, String code) {
		assertEquals(code, TotpCodes.atStep(secret, second / 30));
	}

	@Test
	void acceptsOnlyAnUnusedCodeFromTheCurrentOrPreviousInterval() {
		Instant now = Instant.ofEpochSecond(61);

		assertEquals(OptionalLong.of(1), TotpCodes.matchingStep(secret, "287082", now, 0));
		assertEquals(OptionalLong.of(2), TotpCodes.matchingStep(secret, TotpCodes.atStep(secret, 2), now, 1));
		assertFalse(TotpCodes.matchingStep(secret, "287082", now, 1).isPresent());
		assertFalse(TotpCodes.matchingStep(secret, TotpCodes.atStep(secret, 0), now, -1).isPresent());
		assertFalse(TotpCodes.matchingStep(secret, TotpCodes.atStep(secret, 3), now, -1).isPresent());
		assertFalse(TotpCodes.matchingStep(secret, "287082 ", now, -1).isPresent());
	}

	@Test
	void bindsEncryptedSecretsToTheirAccountAndEnrollment() {
		MfaSecretCipher cipher = new MfaSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
		UUID userID = UUID.randomUUID();
		UUID enrollmentID = UUID.randomUUID();

		byte[] encrypted = cipher.encrypt(userID, enrollmentID, secret);
		assertArrayEquals(secret, cipher.decrypt(userID, enrollmentID, encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(UUID.randomUUID(), enrollmentID, encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(userID, null, encrypted));
	}
}
