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
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserCredentialLookup;

@Component
public class PrivilegedConfirmationService {

	private final ObjectProvider<PrivilegedConfirmationVerifier> verifiers;
	private final PrivilegedConfirmationProofStore proofStore;
	private final AccountStore accounts;
	private final AuthenticatedUserProvider authenticated;
	private final UserCredentialLookup credentials;
	private final CredentialService passwords;
	private final AccountMfaService mfa;
	private final Clock clock;

	public PrivilegedConfirmationService(ObjectProvider<PrivilegedConfirmationVerifier> verifiers,
			PrivilegedConfirmationProofStore proofStore, AccountStore accounts, AuthenticatedUserProvider authenticated,
			UserCredentialLookup credentials, CredentialService passwords, AccountMfaService mfa, Clock clock) {

		this.verifiers = verifiers;
		this.proofStore = proofStore;
		this.accounts = accounts;
		this.authenticated = authenticated;
		this.credentials = credentials;
		this.passwords = passwords;
		this.mfa = mfa;
		this.clock = clock;
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

	@Transactional
	public String issueProof(Action action, String previewDigest, String rawPassword, String totpCode) {

		if (action == null || previewDigest == null || !previewDigest.matches("[0-9a-f]{64}")) {
			throw denied();
		}

		UserAccount principal = authenticated.getCurrentAccount();
		UserAccount current = accounts.findByIdForUpdate(principal.getUserID())
				.filter(id -> id.getUserID().equals(principal.getUserID())).orElseThrow(this::denied);
		AccountLocks.requireCurrentSession(current, principal);
		UserCredential credential = credentials.findByUserId(current.getUserID())
				.filter(found -> current.getUserID().equals(found.getUserId())).orElseThrow(this::denied);

		if (!passwords.verifyPassword(credential, rawPassword)) {
			throw denied();
		}

		AccountMfaService.Verification verificationTotp = mfa.verifyForLogin(current, totpCode);

		if (verificationTotp != Verification.VERIFIED) {
			throw denied();
		}

		String proofToken = generateProofToken();
		String proofHash = digest(proofToken);
		Instant expiresAt = clock.instant().plus(PROOF_TTL);

		Proof proof = new Proof(current.getUserID(), current.getSessionVersion(), action, previewDigest, proofHash,
				expiresAt);

		proofStore.replaceCurrent(proof);

		return proofToken;
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
