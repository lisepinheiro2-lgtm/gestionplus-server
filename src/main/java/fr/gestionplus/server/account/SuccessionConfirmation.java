package fr.gestionplus.server.account;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record SuccessionConfirmation(UUID offerID, UUID userID, long sessionVersion, String sessionIDHash,
		String previewDigest, Instant confirmedAt, Instant expiresAt) {

	public SuccessionConfirmation {

		if (offerID == null || userID == null || sessionVersion < 0
				|| sessionIDHash == null || !sessionIDHash.matches("[0-9a-f]{64}")
				|| previewDigest == null || !previewDigest.matches("[0-9a-f]{64}")
				|| confirmedAt == null || expiresAt == null
				|| !expiresAt.isAfter(confirmedAt)
				|| expiresAt.isAfter(confirmedAt.plus(Duration.ofMinutes(5)))) {

			throw new IllegalArgumentException("La confirmation de succession est invalide.");
		}

	}
}
