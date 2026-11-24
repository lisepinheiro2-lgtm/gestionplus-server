package fr.gestionplus.server.account;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StoredPrivilegedConfirmationVerifier implements PrivilegedConfirmationVerifier {

	private final PrivilegedConfirmationProofStore proofStore;
	private final Clock clock;

	public StoredPrivilegedConfirmationVerifier(PrivilegedConfirmationProofStore proofStore, Clock clock) {
		this.proofStore = proofStore;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean consumeRecentProof(UUID userID, String sessionIDHash, long sessionVersion, Action action,
			String previewDigest, String proof) {

		if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}") || sessionIDHash == null
				|| !sessionIDHash.matches("[0-9a-f]{64}")) {
			return false;
		}

		String proofHash = PrivilegedConfirmationService.digest(proof);

		return proofStore.consumeIfValid(userID, sessionIDHash, sessionVersion, action, previewDigest, proofHash,
				clock.instant());

	}

}
