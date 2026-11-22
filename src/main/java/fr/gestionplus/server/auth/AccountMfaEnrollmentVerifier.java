package fr.gestionplus.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AccountMfaEnrollmentVerifier implements MfaEnrollmentVerifier {

	private final AccountMfaStore accountMfaStore;
	private final Clock clock;

	public AccountMfaEnrollmentVerifier(AccountMfaStore accountMfaStore, ObjectProvider<Clock> clocks) {
		this.accountMfaStore = Objects.requireNonNull(accountMfaStore);
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Override
	public boolean consumeVerifiedIdentity(UUID userID, long sessionVersion, String proof) {
		return accountMfaStore.consumeEnrollmentProof(userID, sessionVersion, hash(proof), clock.instant());
	}

	private static String hash(String proof) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(proof.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Protection de la preuve indisponible.", failure);
		}

	}

}
