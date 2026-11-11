package fr.gestionplus.server.account;

import java.util.UUID;

public interface PrivilegedConfirmationVerifier {

	enum Action {
		STRUCTURE_ADMIN_TRANSFER, SUPER_ADMIN_SUCCESSION_OFFER, SUPER_ADMIN_SUCCESSION_ACCEPTANCE
	}

	boolean consumeRecentProof(UUID userID, long sessionVersion, Action action, String previewDigest, String proof);
}
