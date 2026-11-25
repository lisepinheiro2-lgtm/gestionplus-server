package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.PrivilegedConfirmationProofStore.Proof;
import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AccountMfaService.Verification;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.CurrentPasswordVerifier;
import fr.gestionplus.server.auth.UserAccount;

@Component
public class PrivilegedConfirmationService {

	private final ObjectProvider<PrivilegedConfirmationVerifier> verifiers;
	private final CurrentSessionProvider currentSessionProvider;
	private final PrivilegedConfirmationProofStore proofStore;
	private final AccountStore accounts;
	private final AuthenticatedUserProvider authenticated;
	private final CurrentPasswordVerifier currentPasswordVerifier;
	private final AccountMfaService mfa;
	private final Clock clock;

	public PrivilegedConfirmationService(ObjectProvider<PrivilegedConfirmationVerifier> verifiers,
			CurrentSessionProvider currentSessionProvider, PrivilegedConfirmationProofStore proofStore,
			AccountStore accounts, AuthenticatedUserProvider authenticated,
			CurrentPasswordVerifier currentPasswordVerifier, AccountMfaService mfa, Clock clock) {

		this.verifiers = verifiers;
		this.currentSessionProvider = currentSessionProvider;
		this.proofStore = proofStore;
		this.accounts = accounts;
		this.authenticated = authenticated;
		this.currentPasswordVerifier = currentPasswordVerifier;
		this.mfa = mfa;
		this.clock = clock;
	}

	void require(UserAccount account, Action action, String previewDigest, String proof) {

		PrivilegedConfirmationVerifier verifier = verifiers.getIfAvailable();
		String sessionIDHash = currentSessionProvider.getCurrentSessionIDHash();

		if (sessionIDHash == null) {
			throw denied();
		}

		if (verifier == null || proof == null
				|| !verifier.consumeRecentProof(account.getUserID(), sessionIDHash, account.getSessionVersion(), action,
						previewDigest, proof)) {
			throw new AuditedAccessDeniedException(
					"Une confirmation avec authentification renforcée récente est requise.",
					AuditReason.CONFIRMATION_REQUIRED);
		}

	}

	@Transactional
	public String issueProof(Action action, String previewDigest, String rawPassword, String totpCode) {

		if (action == null || action == Action.SUPER_ADMIN_SUCCESSION_CREATE || previewDigest == null || !previewDigest.matches("[0-9a-f]{64}")) {
			throw denied();
		}

		UserAccount principal = authenticated.getCurrentAccount();
		String sessionIDHash = currentSessionProvider.getCurrentSessionIDHash();

		if (sessionIDHash == null) {
			throw denied();
		}

		UserAccount current = accounts.findByIdForUpdate(principal.getUserID())
				.filter(id -> id.getUserID().equals(principal.getUserID())).orElseThrow(this::denied);
		AccountLocks.requireCurrentSession(current, principal);

		verifyCredentials(current, rawPassword, totpCode);

		return saveProof(current, sessionIDHash, action, previewDigest);
	}

	String saveProof(UserAccount current, String sessionIDHash, Action action, String previewDigest) {
		String proofToken = generateProofToken();
		String proofHash = digest(proofToken);
		Instant expiresAt = clock.instant().plus(PROOF_TTL);
		proofStore.replaceCurrent(new Proof(current.getUserID(), sessionIDHash, current.getSessionVersion(), action,
				previewDigest, proofHash, expiresAt));
		return proofToken;
	}

	void verifyCredentials(UserAccount current, String rawPassword, String totpCode) {

		if (!currentPasswordVerifier.matches(current.getUserID(), rawPassword)) {
			throw denied();
		}

		AccountMfaService.Verification verificationTotp = mfa.verifyForLogin(current, totpCode);

		if (verificationTotp != Verification.VERIFIED) {
			throw denied();
		}

	}

	private String generateProofToken() {

		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
	}

	static String digest(Object preview) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(preview.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 indisponible.", exception);
		}

	}

	private AuditedAccessDeniedException denied() {
		return new AuditedAccessDeniedException("Confirmation renforcée impossible.",
				AuditReason.CONFIRMATION_REQUIRED);
	}

	private static final Duration PROOF_TTL = Duration.ofMinutes(5);

	private final SecureRandom random = new SecureRandom();
}
