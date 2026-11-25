package fr.gestionplus.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountNotificationStore;
import fr.gestionplus.server.account.AccountLocks;
import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditContextProvider;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentProof;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentState;
import fr.gestionplus.server.auth.AccountMfaStore.Factor;

@Service
public class AccountMfaService {

	private final AccountStore accountStore;
	private final AccountMfaStore accountMfaStore;
	private final AuthorizationService authorisationService;
	private final MfaSecretCipher cipher;
	private final ObjectProvider<MfaEnrollmentVerifier> identityVerifier;
	private final LoginService login;
	private final SessionRevocationService sessionRevocation;
	private final AccountNotificationStore notifications;
	private final AuditService audit;
	private final AuditContextProvider auditContext;
	private final SecureRandom random = new SecureRandom();
	private final Clock clock;

	public AccountMfaService(AccountStore accountStore, AccountMfaStore accountMfaStore,
			AuthorizationService authorisationService,
			MfaSecretCipher cipher, ObjectProvider<MfaEnrollmentVerifier> identityVerifier, LoginService login,
			SessionRevocationService sessionRevocation, AccountNotificationStore notifications,
			AuditService audit, AuditContextProvider auditContext, Clock clock) {
		this.accountStore = accountStore;
		this.accountMfaStore = accountMfaStore;
		this.authorisationService = authorisationService;
		this.cipher = cipher;
		this.identityVerifier = identityVerifier;
		this.login = login;
		this.sessionRevocation = sessionRevocation;
		this.notifications = notifications;
		this.audit = audit;
		this.auditContext = auditContext;
		this.clock = clock;
	}

	public boolean requiresVerification(UUID userID) {
		return accountMfaStore.hasState(userID) || authorisationService.requiresMfa(userID);
	}

	@Transactional
	public Verification verifyForLogin(UserAccount snapshot, String code) {
		UserAccount current = accountStore.findByIdForUpdate(snapshot.getUserID()).orElse(null);

		if (!AccountLocks.isCurrentAccount(current, snapshot.getUserID(), snapshot.getSessionVersion())) {
			return Verification.DENIED;
		}

		Factor factor = accountMfaStore.findFactor(current.getUserID()).orElse(null);

		if (factor == null) {
			return authorisationService.requiresMfa(current.getUserID()) ? Verification.DENIED
					: Verification.NOT_REQUIRED;
		}

		if (factor.encryptedSecret() == null || !cipher.available()) {
			return Verification.DENIED;
		}

		byte[] secret = cipher.decrypt(current.getUserID(), null, factor.encryptedSecret());

		try {
			OptionalLong step = TotpCodes.matchingStep(secret, code, clock.instant(), factor.lastStep());
			return step.isPresent() && accountMfaStore.advanceAcceptedStep(current.getUserID(), step.getAsLong())
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

	@AuditedOperation(AuditAction.MFA_ENROLLMENT)
	@Transactional
	public Enrollment beginEnrollment(EnrollmentPrincipal principal) {
		UserAccount current = enrollmentAccount(principal);

		if (!cipher.available() || !requiresInitialEnrollment(current.getUserID())) {
			throw denied();
		}

		EnrollmentState pending = accountMfaStore.findEnrollment(current.getUserID())
				.filter(value -> value.sessionVersion() == current.getSessionVersion()
						&& clock.instant().isBefore(value.expiresAt()))
				.orElse(null);
		Enrollment enrollment;

		if (pending == null) {
			enrollment = prepareEnrollment(current.getUserID(), current.getSessionVersion(), principal.expiresAt());
		} else {
			Instant expiresAt = pending.expiresAt().isBefore(principal.expiresAt())
					? pending.expiresAt()
					: principal.expiresAt();
			byte[] secret = cipher.decrypt(current.getUserID(), pending.enrollmentID(), pending.encryptedSecret());

			try {
				accountMfaStore.saveEnrollment(new EnrollmentState(pending.enrollmentID(), current.getUserID(),
						pending.encryptedSecret(), current.getSessionVersion(), expiresAt));
				enrollment = new Enrollment(pending.enrollmentID(), TotpCodes.setupKey(secret), expiresAt);
			} finally {
				Arrays.fill(secret, (byte) 0);
			}

		}

		record(AuditAction.MFA_ENROLLMENT, current);
		return enrollment;
	}

	@AuditedOperation(AuditAction.MFA_ENROLLMENT)
	@Transactional
	public RecoveryCodes completeEnrollment(EnrollmentPrincipal principal, UUID enrollmentID, String code) {
		return completeEnrollment(enrollmentAccount(principal), enrollmentID, code);
	}

	@AuditedOperation(value = AuditAction.MFA_ENROLLMENT, account = 0)
	@Transactional
	public RecoveryCodes completeEnrollment(UUID userID, UUID enrollmentID, String code) {
		UserAccount current = activeAccount(userID);
		return completeEnrollment(current, enrollmentID, code);
	}

	private RecoveryCodes completeEnrollment(UserAccount current, UUID enrollmentID, String code) {
		UUID userID = current.getUserID();
		EnrollmentState pending = accountMfaStore.findEnrollment(userID).orElseThrow(this::denied);
		Instant now = clock.instant();

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

			accountMfaStore.saveFactor(userID, cipher.encrypt(userID, null, secret), step.getAsLong(),
					codes.stream().map(AccountMfaService::recoveryHash).toList());
			accountMfaStore.removeEnrollment(userID);
			revokeSessions(current);
			record(AuditAction.MFA_ENROLLMENT, current);
			notifications.enqueueMfaEnrollment(userID);
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
				|| !accountMfaStore.consumeRecoveryCode(current.getUserID(), recoveryHash(recoveryCode))) {
			throw denied();
		}

		accountMfaStore.requireNewFactor(current.getUserID());
		revokeSessions(current);
		Enrollment enrollment = prepareEnrollment(current.getUserID(), current.getSessionVersion() + 1);
		record(AuditAction.MFA_RECOVERY, current);
		return enrollment;
	}

	private boolean hasConfiguredFactor(UUID userID) {
		return accountMfaStore.findFactor(userID).filter(factor -> factor.encryptedSecret() != null).isPresent();
	}

	private UserAccount activeAccount(UUID userID) {
		return accountStore.findByIdForUpdate(userID).filter(account -> account.getUserID().equals(userID))
				.filter(account -> account.getStatu() == UserAccountStatus.ACTIVE).orElseThrow(this::denied);
	}

	private Enrollment prepareEnrollment(UUID userID, long sessionVersion) {
		return prepareEnrollment(userID, sessionVersion, clock.instant().plus(EnrollmentPrincipal.VALIDITY));
	}

	private Enrollment prepareEnrollment(UUID userID, long sessionVersion, Instant expiresAt) {
		byte[] secret = new byte[MfaSecretCipher.SECRET_BYTES];
		random.nextBytes(secret);

		try {
			UUID enrollmentID = UUID.randomUUID();
			accountMfaStore.saveEnrollment(
					new EnrollmentState(enrollmentID, userID, cipher.encrypt(userID, enrollmentID, secret),
							sessionVersion, expiresAt));
			return new Enrollment(enrollmentID, TotpCodes.setupKey(secret), expiresAt);
		} finally {
			Arrays.fill(secret, (byte) 0);
		}

	}

	public String issueEnrollmentProof(UUID userID, long sessionVersion) {

		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		String proof = (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
		String hash = recoveryHash(proof);

		accountMfaStore.saveEnrollmentProof(
				new EnrollmentProof(userID, hash, sessionVersion, clock.instant().plus(Duration.ofMinutes(5))));

		return proof;
	}

	private static String recoveryHash(String code) {

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(code.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Protection des codes de récupération indisponible.", failure);
		}

	}

	public boolean requiresInitialEnrollment(UUID userID) {
		return authorisationService.requiresMfa(userID) && !hasConfiguredFactor(userID);
	}

	private void revokeSessions(UserAccount current) {
		accountStore.advanceSessionVersion(current.getUserID());
		record(AuditAction.AUTH_SESSION_REVOKE, current);
		sessionRevocation.revokeAllSessionsAfterCommit(current);
	}

	@Transactional
	public Optional<EnrollmentPrincipal> openContext(String proof) {

		if (proof == null || !proof.matches("[A-Za-z0-9_-]{22}")) {
			return Optional.empty();
		}

		Instant now = clock.instant();
		String proofHash = recoveryHash(proof);
		Optional<EnrollmentProof> consumed = accountMfaStore.consumeEnrollmentProofHash(proofHash, now);

		if (consumed.isEmpty()) {
			return Optional.empty();
		}

		UUID userID = consumed.get().userID();
		Optional<UserAccount> account = accountStore.findByIdForUpdate(userID);

		if (account.isEmpty() || !account.get().getStatu().equals(UserAccountStatus.ACTIVE)
				|| account.get().getSessionVersion() != consumed.get().sessionVersion()
				|| !clock.instant().isBefore(consumed.get().expiresAt()) || !requiresInitialEnrollment(userID)) {
			return Optional.empty();
		}

		return Optional.of(new EnrollmentPrincipal(userID, consumed.get().sessionVersion(),
				clock.instant().plus(EnrollmentPrincipal.VALIDITY)));
	}

	private UserAccount enrollmentAccount(EnrollmentPrincipal principal) {

		if (principal == null) {
			throw denied();
		}

		UserAccount current = activeAccount(principal.userID());

		if (current.getSessionVersion() != principal.sessionVersion() || principal.isExpired(clock.instant())) {
			throw denied();
		}

		return current;
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
