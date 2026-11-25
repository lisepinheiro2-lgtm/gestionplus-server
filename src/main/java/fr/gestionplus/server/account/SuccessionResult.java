package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.auth.AccountMfaService.MfaStatus;

public record SuccessionResult(Outcome outcome, SuccessionOffer offer, SuccessionRevision revision,
		Set<UUID> unavailableAdminChoices) {

	public SuccessionResult {
		unavailableAdminChoices = Set.copyOf(unavailableAdminChoices);
	}

	public enum Outcome {
		APPLIED, NO_CHANGE, REVISION_CHANGED, ADMIN_CHOICES_REQUIRE_REVIEW, CLOSED
	}

	public record Status(SuccessionOffer offer, SuccessionRevision revision, ParticipantStatus outgoing,
			ParticipantStatus successor, Set<UUID> unavailableAdminChoices) {

		public Status {
			unavailableAdminChoices = Set.copyOf(unavailableAdminChoices);
		}
	}

	public record ParticipantStatus(UUID userID, MfaStatus mfa, Instant acceptedAt, Instant validConfirmationUntil) {
	}
}
