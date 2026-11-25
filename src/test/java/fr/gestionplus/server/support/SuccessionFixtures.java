package fr.gestionplus.server.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import fr.gestionplus.server.account.SuccessionOffer;
import fr.gestionplus.server.account.SuccessionPreview;
import fr.gestionplus.server.account.SuccessionPreview.Assignments;
import fr.gestionplus.server.account.SuccessionPreview.LocalRoles;
import fr.gestionplus.server.account.SuccessionPreview.Participant;
import fr.gestionplus.server.account.SuccessionRevision;
import fr.gestionplus.server.auth.UserRole;

public final class SuccessionFixtures {

	private SuccessionFixtures() {
	}

	public static SuccessionRevision revision(SuccessionOffer offer, long number, List<UUID> choices, Instant time) {
		List<LocalRoles> roles = choices.stream().map(id -> new LocalRoles(id, List.of(UserRole.ADMIN))).toList();
		Assignments member = new Assignments(true, false, List.of());
		SuccessionPreview preview = new SuccessionPreview(offer.tenantID(), choices,
				new Participant(offer.outgoingUserID(), new Assignments(true, true, roles), member),
				new Participant(offer.successorUserID(), member, new Assignments(true, true, roles)), choices, true);
		return new SuccessionRevision(offer.offerID(), number, preview, preview.confirmationDigest(), time);
	}
}
