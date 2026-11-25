package fr.gestionplus.server.account;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import fr.gestionplus.server.auth.SessionIDHasher;

public record SuccessionConfirmation(UUID offerID, UUID userID, long revision, long sessionVersion, String sessionIDHash,
		String previewDigest, Instant confirmedAt, Instant expiresAt) {

	public SuccessionConfirmation {

		if (offerID == null || userID == null || revision < 1 || sessionVersion < 0
				|| !SessionIDHasher.isValidHash(sessionIDHash)
				|| previewDigest == null || !previewDigest.matches("[0-9a-f]{64}")
				|| confirmedAt == null || expiresAt == null
				|| !expiresAt.isAfter(confirmedAt)
				|| expiresAt.isAfter(confirmedAt.plus(Duration.ofMinutes(5)))) {

			throw new IllegalArgumentException("La confirmation de succession est invalide.");
		}

	}
}
