package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.auth.UserAccount;

@Component
public class PrivilegedConfirmationService {

	private final ObjectProvider<PrivilegedConfirmationVerifier> verifiers;

	public PrivilegedConfirmationService(ObjectProvider<PrivilegedConfirmationVerifier> verifiers) {
		this.verifiers = verifiers;
	}

	void require(UserAccount account, Action action, String previewDigest, String proof) {
		PrivilegedConfirmationVerifier verifier = verifiers.getIfAvailable();

		if (verifier == null || proof == null || proof.isBlank()
				|| !verifier.consumeRecentProof(account.getUserID(), account.getSessionVersion(), action, previewDigest,
						proof)) {
			throw new AuditedAccessDeniedException(
					"Une confirmation avec authentification renforcée récente est requise.",
					AuditReason.CONFIRMATION_REQUIRED);
		}

	}

	static String digest(Object preview) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(preview.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 indisponible.", exception);
		}

	}
}
