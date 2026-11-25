package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SuccessionRevision(UUID offerID, long revision, SuccessionPreview preview, String previewDigest,
		Instant createdAt) {

	public SuccessionRevision {
		Objects.requireNonNull(offerID);
		Objects.requireNonNull(preview);
		Objects.requireNonNull(createdAt);
		if (revision < 1 || !preview.confirmationDigest().equals(previewDigest)) {
			throw new IllegalArgumentException("La révision de succession est invalide.");
		}
	}
}
