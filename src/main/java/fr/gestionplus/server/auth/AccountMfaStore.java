package fr.gestionplus.server.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountMfaStore {

	boolean hasState(UUID userID);

	Optional<Factor> findFactor(UUID userID);

	boolean advanceAcceptedStep(UUID userID, long step);

	Optional<EnrollmentState> findEnrollment(UUID userID);

	void saveEnrollment(EnrollmentState enrollment);

	void removeEnrollment(UUID userID);

	void saveFactor(UUID userID, byte[] encryptedSecret, long lastStep, List<String> recoveryCodeHashes);

	boolean consumeRecoveryCode(UUID userID, String hash);

	void requireNewFactor(UUID userID);

	void saveEnrollmentProof(EnrollmentProof proof);

	boolean consumeEnrollmentProof(UUID userID, long sessionVersion, String proofHash, Instant now);

	Optional<EnrollmentProof> consumeEnrollmentProofHash(String proofHash, Instant now);

	record Factor(byte[] encryptedSecret, long lastStep) {
	}

	record EnrollmentState(UUID enrollmentID, UUID userID, byte[] encryptedSecret, long sessionVersion,
			Instant expiresAt) {
	}

	record EnrollmentProof(UUID userID, String proofHash, long sessionVersion, Instant expiresAt) {

	}
}
