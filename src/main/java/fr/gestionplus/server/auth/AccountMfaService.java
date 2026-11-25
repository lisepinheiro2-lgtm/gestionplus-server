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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountNotificationStore;
import fr.gestionplus.server.account.AccountLocks;
import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.account.CurrentSessionProvider;
import fr.gestionplus.server.account.SmsIdentityChallengeService;
import fr.gestionplus.server.account.SmsIdentityChallengeService.VerifiedMfaChallenge;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditContextProvider;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentProof;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentPurpose;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentState;
import fr.gestionplus.server.auth.AccountMfaStore.Factor;

@Service
public class AccountMfaService {

	private static final Duration VOLUNTARY_SMS_VALIDITY = Duration.ofMinutes(5);
	private static final Duration VOLUNTARY_SMS_COOLDOWN = Duration.ofSeconds(60);
	private static final Duration VOLUNTARY_SMS_WINDOW = Duration.ofMinutes(15);
	private static final int MAX_VOLUNTARY_SMS = 3;
	private static final Duration VOLUNTARY_SUBMISSION_WINDOW = Duration.ofMinutes(10);
	private static final int MAX_VOLUNTARY_SUBMISSIONS = 5;

	private final AccountStore accountStore;
	private final AccountMfaStore accountMfaStore;
	private final AuthorizationService authorisationService;
	private final SmsIdentityChallengeService smsChallengeService;
	private final CurrentPasswordVerifier passwordVerifier;
	private final RequestRateLimiter rateLimiter;
	private final AuthenticatedUserProvider authenticated;
	private final MfaSecretCipher cipher;
	private final ObjectProvider<MfaEnrollmentVerifier> identityVerifier;
	private final LoginService login;
	private final SessionRevocationService sessionRevocation;
	private final AccountNotificationStore notifications;
	private final AuditService audit;
	private final AuditContextProvider auditContext;
	private final TransactionTemplate transaction;
	private final SecureRandom random = new SecureRandom();
	private final CurrentSessionProvider sessions;
	private final Clock clock;

	public AccountMfaService(AccountStore accountStore, AccountMfaStore accountMfaStore,
			AuthorizationService authorisationService, SmsIdentityChallengeService smsChallengeService,
			CurrentPasswordVerifier passwordVerifier, RequestRateLimiter rateLimiter,
			AuthenticatedUserProvider authenticated, MfaSecretCipher cipher,
			ObjectProvider<MfaEnrollmentVerifier> identityVerifier, LoginService login,
			SessionRevocationService sessionRevocation, AccountNotificationStore notifications, AuditService audit,
			AuditContextProvider auditContext, PlatformTransactionManager transactionManager,
			CurrentSessionProvider sessions, Clock clock) {

		this.accountStore = accountStore;
		this.accountMfaStore = accountMfaStore;
		this.authorisationService = authorisationService;
		this.smsChallengeService = smsChallengeService;
		this.passwordVerifier = passwordVerifier;
		this.rateLimiter = rateLimiter;
		this.authenticated = authenticated;
		this.cipher = cipher;
		this.identityVerifier = identityVerifier;
		this.login = login;
		this.sessionRevocation = sessionRevocation;
		this.notifications = notifications;
		this.audit = audit;
		this.auditContext = auditContext;
		this.transaction = new TransactionTemplate(transactionManager);
		this.sessions = sessions;
		this.clock = clock;
	}

	@AuditedOperation(AuditAction.MFA_VOLUNTARY_SMS_REQUEST)
	@Transactional(propagation = Propagation.NEVER)
	public void requestVoluntaryEnrollment(String rawPassword) {
		UserAccount principal = authenticated.getCurrentAccount();
		requireSubmissionAllowed("mfa-voluntary-enrollment-password", principal.getUserID());

		smsChallengeService.createMfaChallenge(VOLUNTARY_SMS_VALIDITY, MAX_VOLUNTARY_SMS,
				VOLUNTARY_SMS_WINDOW, VOLUNTARY_SMS_COOLDOWN, current -> {
					requireVoluntaryEnrollment(current);
					if (!passwordVerifier.matches(current.getUserID(), rawPassword)) {
						throw new AuditedAccessDeniedException("Vérification MFA impossible.", AuditReason.INVALID_CREDENTIALS);
					}
				});
		record(AuditAction.MFA_VOLUNTARY_SMS_REQUEST, principal);
	}

	@AuditedOperation(AuditAction.MFA_VOLUNTARY_ENROLLMENT_BEGIN)
	@Transactional(propagation = Propagation.NEVER)
	public Enrollment beginVoluntaryEnrollment(String smsCode) {
		VerifiedMfaChallenge verified = smsChallengeService.verifyMfaChallenge(smsCode).orElseThrow(this::denied);
		return transaction.execute(status -> {
			UserAccount current = smsChallengeService.consumeMfaChallenge(verified).orElseThrow(this::denied);
			requireVoluntaryEnrollment(current);

			Enrollment enrollment = prepareEnrollment(current.getUserID(), current.getSessionVersion(),
					clock.instant().plus(EnrollmentPrincipal.VALIDITY), EnrollmentPurpose.VOLUNTARY,
					verified.sessionIDHash());
			record(AuditAction.MFA_VOLUNTARY_ENROLLMENT_BEGIN, current);
			return enrollment;
		});
	}

	@AuditedOperation(AuditAction.MFA_VOLUNTARY_ENROLLMENT_COMPLETE)
	@Transactional(propagation = Propagation.NEVER)
	public RecoveryCodes completeVoluntaryEnrollment(UUID enrollmentID, String code) {
		UserAccount principal = authenticated.getCurrentAccount();
		requireSubmissionAllowed("mfa-voluntary-enrollment-totp", principal.getUserID());

		return transaction.execute(status -> {
			UserAccount current = activeAccount(principal.getUserID());
			AccountLocks.requireCurrentSession(current, principal);
			requireVoluntaryEnrollment(current);
			String sessionIDHash = sessions.getActiveSessionIDHash();
			if (!SessionIDHasher.isValidHash(sessionIDHash)) {
				throw new AuditedAccessDeniedException("Une session HTTP active est requise.", AuditReason.STALE_SESSION);
			}
			return completeEnrollment(current, enrollmentID, code, EnrollmentPurpose.VOLUNTARY, sessionIDHash);
		});
	}

	private void requireSubmissionAllowed(String scope, UUID userID) {
		RequestRateLimiter.Decision decision = rateLimiter.acquireForAccount(scope, userID,
				MAX_VOLUNTARY_SUBMISSIONS, VOLUNTARY_SUBMISSION_WINDOW);
		if (!decision.allowed()) {
			throw new RequestRateLimitExceededException(decision.retryAfter());
		}
	}

	private void requireVoluntaryEnrollment(UserAccount current) {
		if (!current.isPhoneVerified() || accountMfaStore.hasState(current.getUserID())) {
			throw denied();
		}
		if (!cipher.available()) {
			throw new DistributedSecurityUnavailableException();
		}
	}

	public boolean requiresVerification(UUID userID) {
		return accountMfaStore.hasState(userID) || authorisationService.requiresMfa(userID);
	}

	/** Reads the persisted factor and checks local key availability without consuming a TOTP. */
	public MfaStatus getMfaStatus(UUID userID) {
		Objects.requireNonNull(userID);
		try {
			Factor factor = accountMfaStore.findFactor(userID).orElse(null);
			if (factor != null && factor.encryptedSecret() == null) {
				return MfaStatus.RECOVERY_REQUIRED;
			}
			if (!cipher.available()) {
				return MfaStatus.UNAVAILABLE;
			}
			if (factor == null) {
				return MfaStatus.NOT_CONFIGURED;
			}

			byte[] secret = cipher.decrypt(userID, null, factor.encryptedSecret());
			Arrays.fill(secret, (byte) 0);
			return MfaStatus.CONFIGURED;
		} catch (DataAccessException | IllegalStateException failure) {
			return MfaStatus.UNAVAILABLE;
		}
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

		if (!cipher.available() || accountMfaStore.hasState(userID) || verifier == null || identityProof == null
				|| identityProof.isBlank()
				|| !verifier.consumeVerifiedIdentity(userID, current.getSessionVersion(), identityProof)) {
			throw denied();
		}

		Enrollment enrollment = prepareEnrollment(userID, current.getSessionVersion(),
				clock.instant().plus(EnrollmentPrincipal.VALIDITY), EnrollmentPurpose.INITIAL, null);
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

		EnrollmentState pending = accountMfaStore.findEnrollment(current.getUserID(), EnrollmentPurpose.INITIAL)
				.filter(value -> value.sessionVersion() == current.getSessionVersion()
						&& clock.instant().isBefore(value.expiresAt()))
				.orElse(null);
		Enrollment enrollment;

		if (pending == null) {
			enrollment = prepareEnrollment(current.getUserID(), current.getSessionVersion(), principal.expiresAt(),
					EnrollmentPurpose.INITIAL, null);
		} else {
			Instant expiresAt = pending.expiresAt().isBefore(principal.expiresAt())
					? pending.expiresAt()
					: principal.expiresAt();
			byte[] secret = cipher.decrypt(current.getUserID(), pending.enrollmentID(), pending.encryptedSecret());

			try {
				accountMfaStore.saveEnrollment(new EnrollmentState(pending.enrollmentID(), current.getUserID(),
						pending.encryptedSecret(), current.getSessionVersion(), expiresAt, EnrollmentPurpose.INITIAL, null));
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
		return completeEnrollment(enrollmentAccount(principal), enrollmentID, code, EnrollmentPurpose.INITIAL, null);
	}

	@AuditedOperation(value = AuditAction.MFA_ENROLLMENT, account = 0)
	@Transactional
	public RecoveryCodes completeEnrollment(UUID userID, UUID enrollmentID, String code) {
		UserAccount current = activeAccount(userID);
		EnrollmentPurpose purpose = accountMfaStore.hasState(userID) ? EnrollmentPurpose.RECOVERY : EnrollmentPurpose.INITIAL;
		return completeEnrollment(current, enrollmentID, code, purpose, null);
	}

	private RecoveryCodes completeEnrollment(UserAccount current, UUID enrollmentID, String code,
			EnrollmentPurpose purpose, String sessionIDHash) {
		UUID userID = current.getUserID();
		EnrollmentState pending = accountMfaStore.findEnrollment(userID, purpose).orElseThrow(this::denied);
		Instant now = clock.instant();

		if (!pending.enrollmentID().equals(enrollmentID) || !now.isBefore(pending.expiresAt())
				|| pending.sessionVersion() != current.getSessionVersion() || !factorAllowsEnrollment(userID, purpose)
				|| !Objects.equals(pending.sessionIDHash(), sessionIDHash)) {
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
			accountMfaStore.removeEnrollments(userID);
			revokeSessions(current);
			notifications.enqueueMfaEnrollment(userID);
			record(purpose == EnrollmentPurpose.VOLUNTARY ? AuditAction.MFA_VOLUNTARY_ENROLLMENT_COMPLETE
					: AuditAction.MFA_ENROLLMENT, current);
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
		Enrollment enrollment = prepareEnrollment(current.getUserID(), current.getSessionVersion() + 1,
				clock.instant().plus(EnrollmentPrincipal.VALIDITY), EnrollmentPurpose.RECOVERY, null);
		record(AuditAction.MFA_RECOVERY, current);
		return enrollment;
	}

	private boolean factorAllowsEnrollment(UUID userID, EnrollmentPurpose purpose) {
		Factor factor = accountMfaStore.findFactor(userID).orElse(null);
		return purpose == EnrollmentPurpose.RECOVERY ? factor != null && factor.encryptedSecret() == null
				: factor == null;
	}

	private UserAccount activeAccount(UUID userID) {
		return accountStore.findByIdForUpdate(userID).filter(account -> account.getUserID().equals(userID))
				.filter(account -> account.getStatu() == UserAccountStatus.ACTIVE).orElseThrow(this::denied);
	}

	private Enrollment prepareEnrollment(UUID userID, long sessionVersion, Instant expiresAt,
			EnrollmentPurpose purpose, String sessionIDHash) {
		byte[] secret = new byte[MfaSecretCipher.SECRET_BYTES];
		random.nextBytes(secret);

		try {
			UUID enrollmentID = UUID.randomUUID();
			accountMfaStore.saveEnrollment(
					new EnrollmentState(enrollmentID, userID, cipher.encrypt(userID, enrollmentID, secret),
							sessionVersion, expiresAt, purpose, sessionIDHash));
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
		return authorisationService.requiresMfa(userID) && !accountMfaStore.hasState(userID);
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

	public enum MfaStatus {
		NOT_CONFIGURED, CONFIGURED, RECOVERY_REQUIRED, UNAVAILABLE
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
