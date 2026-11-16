package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ActivationIdentityChallengeStore {

	void replaceCurrent(ActivationIdentityChallenge newChallenge, Instant revokedAt);

	int recordFailedAttempt(UUID challengeID, Instant attemptedAt);

	boolean consumeIfCurrent(UUID challengeID, Instant now);

	Optional<ActivationIdentityChallenge> findCurrentByInvitationAndUser(UUID invitationID, UUID userID);

	record ActivationIdentityChallenge(UUID challengeID, UUID userID, String phoneNumber, UUID invitationID,
			String codeDigest, int failedAttempts, Instant createdAt, Instant expiresAt, Instant consumedAt,
			Instant revokedAt) {
		public static final int MAX_FAILED_ATTEMPTS = 3;

		public boolean isUsable(Instant now) {
			return codeDigest() != null && createdAt() != null && expiresAt() != null
					&& now.isBefore(expiresAt()) && failedAttempts() < MAX_FAILED_ATTEMPTS && revokedAt() == null
					&& consumedAt() == null;
		}
	}
}
