package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.UUID;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;

public interface PrivilegedConfirmationProofStore {

	boolean consumeIfValid(UUID userID, long sessionVersion, Action action, String previewDigest, String proofHash,
			Instant now);

	void replaceCurrent(Proof proof);

	record Proof(UUID userID, long sessionVersion, Action action,
			String previewDigest, String proofHash, Instant expiresAt) {

		public Proof {

			if (userID == null || action == null || expiresAt == null || previewDigest == null || proofHash == null
					|| previewDigest.isBlank() || proofHash.isBlank() || sessionVersion < 0) {
				throw new IllegalArgumentException("La preuve est invalide.");
			}

		}
	}
}
