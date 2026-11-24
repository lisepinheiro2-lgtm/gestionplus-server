package fr.gestionplus.server.account;

import java.util.UUID;

public interface PrivilegedConfirmationVerifier {

	enum Action {
		STRUCTURE_ADMIN_TRANSFER, SUPER_ADMIN_SUCCESSION_CREATE
	}

	boolean consumeRecentProof(UUID userID, String sessionIDHash, long sessionVersion, Action action,
			String previewDigest, String proof);
}
