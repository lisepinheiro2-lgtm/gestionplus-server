package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MfaSecretCipherTest {

	private final byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
	private final ApplicationContextRunner production = new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
			.withUserConfiguration(MfaSecretCipher.class, MfaProductionConfiguration.class);

	@Test
	void productionRefusesAnAbsentKeyAtStartup() {
		production.run(context -> assertNotNull(context.getStartupFailure()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "invalid-private-key", "AA==" })
	void productionRefusesBlankMalformedOrWrongSizeKeys(String key) {
		production.withPropertyValues("gestionplus.mfa.key=" + key)
				.run(context -> assertNotNull(context.getStartupFailure()));
	}

	@Test
	void productionAcceptsAnExternal256BitKey() {
		production.withPropertyValues("gestionplus.mfa.key=" + key()).run(context -> {
			assertNull(context.getStartupFailure());
			assertTrue(context.getBean(MfaSecretCipher.class).available());
			assertNotNull(context.getBean(MfaProductionConfiguration.class));
		});
	}

	@Test
	void invalidKeyDiagnosticsDoNotRepeatTheConfiguredValue() {
		String configured = "invalid-private-key";

		production.withPropertyValues("gestionplus.mfa.key=" + configured).run(context -> {
			assertNotNull(context.getStartupFailure());

			for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
				assertFalse(String.valueOf(cause.getMessage()).contains(configured));
			}

		});
	}

	@Test
	void anUnconfiguredDevelopmentCipherCannotEncryptASecret() {
		MfaSecretCipher cipher = new MfaSecretCipher("");
		assertFalse(cipher.available());
		assertThrows(IllegalStateException.class, () -> cipher.encrypt(UUID.randomUUID(), null, secret));
	}

	@Test
	void encryptsWithFreshNoncesAndSurvivesRestartWithTheSameKey() {
		String key = key();
		MfaSecretCipher cipher = new MfaSecretCipher(key);
		UUID userID = UUID.randomUUID();
		UUID enrollmentID = UUID.randomUUID();
		byte[] first = cipher.encrypt(userID, enrollmentID, secret);
		byte[] second = cipher.encrypt(userID, enrollmentID, secret);

		assertFalse(Arrays.equals(first, second));
		assertFalse(new String(first, StandardCharsets.ISO_8859_1)
				.contains(new String(secret, StandardCharsets.US_ASCII)));
		assertArrayEquals(secret, new MfaSecretCipher(key).decrypt(userID, enrollmentID, first));
		assertArrayEquals(secret, cipher.decrypt(userID, enrollmentID, second));
	}

	@Test
	void rejectsAnotherKeyAccountOrEnrollmentWithoutExposingTheSecret() {
		MfaSecretCipher cipher = new MfaSecretCipher(key());
		UUID userID = UUID.randomUUID();
		UUID enrollmentID = UUID.randomUUID();
		byte[] encrypted = cipher.encrypt(userID, enrollmentID, secret);

		assertThrows(IllegalStateException.class, () -> cipher.decrypt(UUID.randomUUID(), enrollmentID, encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(userID, UUID.randomUUID(), encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(userID, null, encrypted));
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new MfaSecretCipher(key()).decrypt(userID, enrollmentID, encrypted));
		assertFalse(failure.getMessage().contains(new String(secret, StandardCharsets.US_ASCII)));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 12, 48, 50, 1024 })
	void rejectsMalformedEnvelopeLengths(int length) {
		MfaSecretCipher cipher = new MfaSecretCipher(key());
		assertThrows(IllegalStateException.class,
				() -> cipher.decrypt(UUID.randomUUID(), null, new byte[length]));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 13, 48 })
	void detectsVersionNonceCiphertextAndTagTampering(int offset) {
		MfaSecretCipher cipher = new MfaSecretCipher(key());
		UUID userID = UUID.randomUUID();
		byte[] encrypted = cipher.encrypt(userID, null, secret);
		encrypted[offset] ^= 1;

		assertThrows(IllegalStateException.class, () -> cipher.decrypt(userID, null, encrypted));
	}

	private String key() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}
}
