package fr.gestionplus.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditContextProvider;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentState;
import fr.gestionplus.server.auth.AccountMfaStore.Factor;

@Service
public class AccountMfaService {

	private final AccountStore accounts;
	private final AccountMfaStore store;
	private final AuthorizationService authorization;
	private final MfaSecretCipher cipher;
	private final ObjectProvider<MfaEnrollmentVerifier> identityVerifier;
	private final LoginService login;
	private final SessionRevocationService sessions;
	private final AuditService audit;
	private final AuditContextProvider auditContext;
	private final SecureRandom random = new SecureRandom();

	public AccountMfaService(AccountStore accounts, AccountMfaStore store, AuthorizationService authorization,
			MfaSecretCipher cipher, ObjectProvider<MfaEnrollmentVerifier> identityVerifier, LoginService login,
			SessionRevocationService sessions, AuditService audit, AuditContextProvider auditContext) {
		this.accounts = accounts;
		this.store = store;
		this.authorization = authorization;
		this.cipher = cipher;
		this.identityVerifier = identityVerifier;
		this.login = login;
		this.sessions = sessions;
		this.audit = audit;
		this.auditContext = auditContext;
	}

	public boolean requiresVerification(UUID userID) {
		return store.hasState(userID) || authorization.requiresMfa(userID);
	}

	@Transactional
	public Verification verifyForLogin(UserAccount snapshot, String code) {
		UserAccount current = accounts.findByIdForUpdate(snapshot.getUserID()).orElse(null);

		if (current == null || !current.getUserID().equals(snapshot.getUserID())
				|| current.getStatu() != UserAccountStatus.ACTIVE
				|| current.getSessionVersion() != snapshot.getSessionVersion()) {
			return Verification.DENIED;
		}

		Factor factor = store.findFactor(current.getUserID()).orElse(null);

		if (factor == null) {
			return authorization.requiresMfa(current.getUserID()) ? Verification.DENIED : Verification.NOT_REQUIRED;
		}

		if (factor.encryptedSecret() == null || !cipher.available()) {
			return Verification.DENIED;
		}

		byte[] secret = cipher.decrypt(current.getUserID(), null, factor.encryptedSecret());

		try {
			OptionalLong step = TotpCodes.matchingStep(secret, code, Instant.now(), factor.lastStep());
			return step.isPresent() && store.advanceAcceptedStep(current.getUserID(), step.getAsLong())
					? Verification.VERIFIED
					: Verification.DENIED;
		} finally {
			Arrays.fill(secret, (byte) 0);
		}

	}

	@AuditedOperation(value = AuditAction.MFA_ENROLLMENT, account = 0)
	@Transactional
	public Enrollment beginEnrollment(UUID userID, String identityProof) {
		UserAccount current = activeAccount(userID);
		MfaEnrollmentVerifier verifier = identityVerifier.getIfAvailable();

		if (!cipher.available() || hasConfiguredFactor(userID) || verifier == null || identityProof == null
				|| identityProof.isBlank()
				|| !verifier.consumeVerifiedIdentity(userID, current.getSessionVersion(), identityProof)) {
			throw denied();
		}

		Enrollment enrollment = prepareEnrollment(userID, current.getSessionVersion());
		record(AuditAction.MFA_ENROLLMENT, current);
		return enrollment;
	}

	@AuditedOperation(value = AuditAction.MFA_ENROLLMENT, account = 0)
	@Transactional
	public RecoveryCodes completeEnrollment(UUID userID, UUID enrollmentID, String code) {
		UserAccount current = activeAccount(userID);
		EnrollmentState pending = store.findEnrollment(userID).orElseThrow(this::denied);
		Instant now = Instant.now();

		if (!pending.enrollmentID().equals(enrollmentID) || !now.isBefore(pending.expiresAt())
				|| pending.sessionVersion() != current.getSessionVersion() || hasConfiguredFactor(userID)) {
			throw denied();
		}

		byte[] secret = cipher.decrypt(userID, enrollmentID, pending.encryptedSecret());

		try {
			OptionalLong step = TotpCodes.matchingStep(secret, code, now, -1);

			if (step.isEmpty()) {
				throw denied();
			}

			List<String> codes = new ArrayList<>();

			for (int index = 0; index < 10; index++) {
				byte[] bytes = new byte[16];
				random.nextBytes(bytes);
				codes.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
			}

			store.saveFactor(userID, cipher.encrypt(userID, null, secret), step.getAsLong(),
					codes.stream().map(AccountMfaService::recoveryHash).toList());
			store.removeEnrollment(userID);
			revokeSessions(current);
			record(AuditAction.MFA_ENROLLMENT, current);
			return new RecoveryCodes(codes);
		} finally {
			Arrays.fill(secret, (byte) 0);
		}

	}

	@AuditedOperation(AuditAction.MFA_RECOVERY)
	@Transactional
	public Enrollment recover(String userName, String password, String recoveryCode) {

		if (recoveryCode == null || !recoveryCode.matches("[A-Za-z0-9_-]{22}") || !cipher.available()) {
			throw denied();
		}

		LoginService.LoginAttempt attempt = login.evaluate(userName, password);

		if (attempt.account() == null) {
			throw denied();
		}

		UserAccount current = activeAccount(attempt.account().getUserID());

		if (current.getSessionVersion() != attempt.account().getSessionVersion()
				|| !store.consumeRecoveryCode(current.getUserID(), recoveryHash(recoveryCode))) {
			throw denied();
		}

		store.requireNewFactor(current.getUserID());
		revokeSessions(current);
		Enrollment enrollment = prepareEnrollment(current.getUserID(), current.getSessionVersion() + 1);
		record(AuditAction.MFA_RECOVERY, current);
		return enrollment;
	}

	private boolean hasConfiguredFactor(UUID userID) {
		return store.findFactor(userID).filter(factor -> factor.encryptedSecret() != null).isPresent();
	}

	private UserAccount activeAccount(UUID userID) {
		return accounts.findByIdForUpdate(userID).filter(account -> account.getUserID().equals(userID))
				.filter(account -> account.getStatu() == UserAccountStatus.ACTIVE).orElseThrow(this::denied);
	}

	private Enrollment prepareEnrollment(UUID userID, long sessionVersion) {
		byte[] secret = new byte[MfaSecretCipher.SECRET_BYTES];
		random.nextBytes(secret);

		try {
			UUID enrollmentID = UUID.randomUUID();
			Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
			store.saveEnrollment(new EnrollmentState(enrollmentID, userID, cipher.encrypt(userID, enrollmentID, secret),
					sessionVersion, expiresAt));
			return new Enrollment(enrollmentID, TotpCodes.setupKey(secret), expiresAt);
		} finally {
			Arrays.fill(secret, (byte) 0);
		}

	}

	private static String recoveryHash(String code) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(code.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Protection des codes de récupération indisponible.", failure);
		}

	}

	private void revokeSessions(UserAccount current) {
		accounts.advanceSessionVersion(current.getUserID());
		record(AuditAction.AUTH_SESSION_REVOKE, current);
		sessions.revokeAllSessionsAfterCommit(current);
	}

	private void record(AuditAction action, UserAccount current) {
		audit.recordForActor(action, AuditResult.SUCCESS, current.getUserID(), auditContext.getIpAddress(),
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, current.getUserID())), AuditMetadata.empty());
	}

	private AccessDeniedException denied() {
		return new AccessDeniedException("Vérification MFA impossible.");
	}

	public enum Verification {
		NOT_REQUIRED, VERIFIED, DENIED
	}

	public record Enrollment(UUID enrollmentID, String setupKey, Instant expiresAt) {
		@Override
		public String toString() {
			return "Enrollment[enrollmentID=" + enrollmentID + ", expiresAt=" + expiresAt + "]";
		}
	}

	public record RecoveryCodes(List<String> codes) {
		public RecoveryCodes { codes = List.copyOf(codes); }

		@Override
		public String toString() {
			return "RecoveryCodes[protected]";
		}
	}
}
