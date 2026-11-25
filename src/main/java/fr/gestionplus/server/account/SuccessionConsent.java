package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SuccessionConsent(UUID offerID, UUID userID, long revision, Instant acceptedAt) {
	public SuccessionConsent {
		Objects.requireNonNull(offerID);
		Objects.requireNonNull(userID);
		Objects.requireNonNull(acceptedAt);
		if (revision < 1) {
			throw new IllegalArgumentException("La révision du consentement est invalide.");
		}
	}
}
