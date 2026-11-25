package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActivationCodeDigesterTest {

	private static final UUID CHALLENGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	@Test
	void computesTheExpectedHmacForTheChallengeAndCode() {
		ActivationCodeDigester digester = new ActivationCodeDigester(KEY);

		assertEquals("087f40bb9857ebf5ede2dbaf7b4b5fa23cdb402d8fce44380a10c2c4688da30a",
				digester.digest(CHALLENGE_ID, "000042"));
	}

	@Test
	void reproducesTheSameDigestAcrossCallsAndInstances() {
		ActivationCodeDigester digester = new ActivationCodeDigester(KEY);
		String digest = digester.digest(CHALLENGE_ID, "000042");

		assertEquals(digest, digester.digest(CHALLENGE_ID, "000042"));
		assertEquals(digest, new ActivationCodeDigester(KEY).digest(CHALLENGE_ID, "000042"));
	}

	@Test
	void bindsTheDigestToTheChallenge() {
		ActivationCodeDigester digester = new ActivationCodeDigester(KEY);
		UUID anotherChallengeID = UUID.fromString("00000000-0000-0000-0000-000000000002");

		assertNotEquals(digester.digest(CHALLENGE_ID, "000042"),
				digester.digest(anotherChallengeID, "000042"));
	}

	@Test
	void separatesActivationAndMfaEvenForTheSameChallengeAndCode() {
		ActivationCodeDigester digester = new ActivationCodeDigester(KEY);
		assertEquals(digester.digest(CHALLENGE_ID, "000042"),
				digester.digest(SmsIdentityChallenge.Purpose.ACCOUNT_ACTIVATION, CHALLENGE_ID, "000042"));
		assertNotEquals(digester.digest(CHALLENGE_ID, "000042"),
				digester.digest(SmsIdentityChallenge.Purpose.MFA_ENROLLMENT, CHALLENGE_ID, "000042"));
	}

	@Test
	void bindsTheDigestToTheCode() {
		ActivationCodeDigester digester = new ActivationCodeDigester(KEY);

		assertNotEquals(digester.digest(CHALLENGE_ID, "000042"),
				digester.digest(CHALLENGE_ID, "000043"));
	}

	@Test
	void bindsTheDigestToTheSecretKey() {
		byte[] anotherKey = new byte[32];
		Arrays.fill(anotherKey, (byte) 1);
		String configuredKey = Base64.getEncoder().encodeToString(anotherKey);

		assertNotEquals(new ActivationCodeDigester(KEY).digest(CHALLENGE_ID, "000042"),
				new ActivationCodeDigester(configuredKey).digest(CHALLENGE_ID, "000042"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t" })
	void refusesDigestingWithoutAConfiguredKey(String configuredKey) {
		ActivationCodeDigester digester = new ActivationCodeDigester(configuredKey);

		assertThrows(IllegalStateException.class, () -> digester.digest(CHALLENGE_ID, "000042"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "invalid-key", "!!!!", "AA=A" })
	void rejectsMalformedBase64Keys(String configuredKey) {
		assertThrows(IllegalArgumentException.class, () -> new ActivationCodeDigester(configuredKey));
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 16, 31, 33, 64 })
	void rejectsKeysThatDoNotContain256Bits(int length) {
		String configuredKey = Base64.getEncoder().encodeToString(new byte[length]);

		assertThrows(IllegalArgumentException.class, () -> new ActivationCodeDigester(configuredKey));
	}
}
