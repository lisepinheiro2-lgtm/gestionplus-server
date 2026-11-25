package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import fr.gestionplus.server.auth.SessionIDHasher;

public record SmsIdentityChallenge(UUID challengeID, Binding binding, String codeDigest,
		int failedAttempts, Instant createdAt, Instant expiresAt, Instant consumedAt,
		Instant revokedAt, Instant sendConfirmedAt, Instant sendRecoveryAt) {

	public static final int MAX_FAILED_ATTEMPTS = 3;

	public SmsIdentityChallenge {
		Objects.requireNonNull(binding);
		if (challengeID == null || codeDigest == null
				|| failedAttempts < 0 || failedAttempts > MAX_FAILED_ATTEMPTS
				|| createdAt == null || expiresAt == null || sendRecoveryAt == null
				|| !sendRecoveryAt.isAfter(createdAt)
				|| (consumedAt != null && revokedAt != null)
				|| (failedAttempts == MAX_FAILED_ATTEMPTS && revokedAt == null)
				|| (sendConfirmedAt != null && (sendConfirmedAt.isBefore(createdAt)
						|| !sendConfirmedAt.isBefore(sendRecoveryAt)))) {
			throw new IllegalArgumentException("Le défi SMS est invalide.");
		}
		if (binding.purpose() == Purpose.MFA_ENROLLMENT
				&& (!codeDigest.matches("[0-9a-f]{64}") || !expiresAt.isAfter(createdAt))) {
			throw new IllegalArgumentException("Le défi SMS MFA est invalide.");
		}
	}

	public static SmsIdentityChallenge forActivation(UUID challengeID, UUID userID, String phoneNumber,
			UUID invitationID, String codeDigest, int failedAttempts, Instant createdAt, Instant expiresAt,
			Instant consumedAt, Instant revokedAt, Instant sendConfirmedAt, Instant sendRecoveryAt) {
		return new SmsIdentityChallenge(challengeID, Binding.forActivation(invitationID, userID, phoneNumber),
				codeDigest, failedAttempts, createdAt, expiresAt, consumedAt, revokedAt, sendConfirmedAt, sendRecoveryAt);
	}

	public boolean isUsable(Instant now) {
		return sendConfirmedAt() != null && isCurrent(now);
	}

	public boolean isCurrent(Instant now) {
		return now.isBefore(expiresAt()) && failedAttempts() < MAX_FAILED_ATTEMPTS
				&& revokedAt() == null && consumedAt() == null;
	}

	@Override
	public String toString() {
		return "SmsIdentityChallenge[challengeID=" + challengeID + ", purpose=" + binding.purpose() + "]";
	}

	public enum Purpose {
		ACCOUNT_ACTIVATION,
		MFA_ENROLLMENT
	}

	public record Binding(UUID userID, String phoneNumber, Purpose purpose, UUID invitationID,
			String sessionIDHash, Long sessionVersion) {

		public Binding {
			if (userID == null || purpose == null || phoneNumber == null) {
				throw new IllegalArgumentException("Le contexte du défi SMS est incomplet.");
			}
			switch (purpose) {
			case ACCOUNT_ACTIVATION -> {
				if (invitationID == null || sessionIDHash != null || sessionVersion != null) {
					throw new IllegalArgumentException("Le contexte d'activation est invalide.");
				}
			}
			case MFA_ENROLLMENT -> {
				if (invitationID != null || !SessionIDHasher.isValidHash(sessionIDHash)
						|| sessionVersion == null || sessionVersion < 0 || !phoneNumber.matches("\\+[0-9]{1,15}")) {
					throw new IllegalArgumentException("Le contexte d'enrôlement MFA est invalide.");
				}
			}
			}
		}

		public static Binding forActivation(UUID invitationID, UUID userID, String phoneNumber) {
			return new Binding(userID, phoneNumber, Purpose.ACCOUNT_ACTIVATION, invitationID, null, null);
		}

		public static Binding forMfaEnrollment(UUID userID, String phoneNumber, String sessionIDHash,
				long sessionVersion) {
			return new Binding(userID, phoneNumber, Purpose.MFA_ENROLLMENT, null, sessionIDHash, sessionVersion);
		}

		@Override
		public String toString() {
			return "Binding[userID=" + userID + ", purpose=" + purpose + "]";
		}
	}
}
