package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SuccessionOffer(UUID offerID, UUID outgoingUserID, UUID successorUserID, UUID tenantID,
		List<UUID> adminRolesToTransfer, String previewDigest, OfferStatus status, Instant createdAt, Instant expiresAt) {

	public SuccessionOffer {
		Objects.requireNonNull(offerID);
		Objects.requireNonNull(outgoingUserID);
		Objects.requireNonNull(successorUserID);
		Objects.requireNonNull(tenantID);
		Objects.requireNonNull(createdAt);
		Objects.requireNonNull(expiresAt);
		Objects.requireNonNull(previewDigest);
		Objects.requireNonNull(status);
		adminRolesToTransfer = List.copyOf(adminRolesToTransfer);

		if (!previewDigest.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Empreinte invalide.");
		}

		if (outgoingUserID.equals(successorUserID)) {
			throw new IllegalArgumentException("Les participants doivent être distincts.");
		}

		if (!createdAt.isBefore(expiresAt)) {
			throw new IllegalArgumentException("Les dates sont incohérentes.");
		}

	}

	public enum OfferStatus {
		PENDING,
		ACCEPTED,
		CANCELLED,
		COMPLETED
	}
}
