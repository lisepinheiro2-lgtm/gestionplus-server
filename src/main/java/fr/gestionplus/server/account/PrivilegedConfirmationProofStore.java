package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.UUID;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;

public interface PrivilegedConfirmationProofStore {

	boolean consumeIfValid(UUID userID, String sessionIDHash, long sessionVersion, Action action, String previewDigest,
			String proofHash, Instant now);

	void replaceCurrent(Proof proof);

	record Proof(UUID userID, String sessionIDHash, long sessionVersion, Action action,
			String previewDigest, String proofHash, Instant expiresAt) {

		public Proof {

			if (userID == null || sessionIDHash == null || !sessionIDHash.matches("[0-9a-f]{64}") || action == null
					|| expiresAt == null || previewDigest == null || !previewDigest.matches("[0-9a-f]{64}")
					|| proofHash == null || !proofHash.matches("[0-9a-f]{64}") || sessionVersion < 0) {
				throw new IllegalArgumentException("La preuve est invalide.");
			}

		}
	}
}
