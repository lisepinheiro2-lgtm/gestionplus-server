package fr.gestionplus.server.account;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SuccessionOffer(UUID offerID, UUID outgoingUserID, UUID successorUserID, UUID tenantID,
		List<UUID> adminRolesToTransfer, Long currentRevision, OfferStatus status, Instant createdAt,
		Instant expiresAt, ClosureReason closureReason, Instant closedAt) {

	public SuccessionOffer {
		Objects.requireNonNull(offerID);
		Objects.requireNonNull(outgoingUserID);
		Objects.requireNonNull(successorUserID);
		Objects.requireNonNull(tenantID);
		Objects.requireNonNull(createdAt);
		Objects.requireNonNull(expiresAt);
		Objects.requireNonNull(status);
		adminRolesToTransfer = currentRevision == null ? List.copyOf(adminRolesToTransfer)
				: SuccessionPreview.sortedIDs(adminRolesToTransfer);

		if (outgoingUserID.equals(successorUserID) || !createdAt.isBefore(expiresAt)
				|| (currentRevision == null && status.isOpen())
				|| (currentRevision != null && (currentRevision < 1
						|| !expiresAt.equals(createdAt.plus(Duration.ofHours(24)))))
				|| (status.isOpen() && (closureReason != null || closedAt != null))
				|| (closureReason != null && (closedAt == null || closureReason.status() != status))
				|| (currentRevision != null && !status.isOpen() && closureReason == null)) {
			throw new IllegalArgumentException("La proposition de succession est invalide.");
		}
	}

	public boolean hasParticipant(UUID userID) {
		return outgoingUserID.equals(userID) || successorUserID.equals(userID);
	}

	public boolean isCurrentRevision(long revision) {
		return currentRevision != null && currentRevision == revision;
	}

	public boolean isOpenAt(Instant at) {
		return status.isOpen() && !createdAt.isAfter(at) && at.isBefore(expiresAt);
	}

	public enum OfferStatus {
		PENDING, ACCEPTED, CANCELLED, COMPLETED, EXPIRED;

		public boolean isOpen() {
			return this == PENDING || this == ACCEPTED;
		}
	}

	public enum ClosureReason {
		CANCELLED_BY_OUTGOING(OfferStatus.CANCELLED),
		WITHDRAWN_BY_SUCCESSOR(OfferStatus.CANCELLED),
		ELIGIBILITY_LOST(OfferStatus.CANCELLED),
		LEGACY_INCOMPATIBLE(OfferStatus.CANCELLED),
		TRANSFERRED(OfferStatus.COMPLETED),
		EXPIRATION(OfferStatus.EXPIRED);

		private final OfferStatus status;

		ClosureReason(OfferStatus status) {
			this.status = status;
		}

		public OfferStatus status() {
			return status;
		}
	}
}
