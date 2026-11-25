package fr.gestionplus.server.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.account.SuccessionOffer.OfferStatus;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.RequestRateLimitExceededException;
import fr.gestionplus.server.auth.RequestRateLimiter;
import fr.gestionplus.server.auth.SessionIDHasher;
import fr.gestionplus.server.auth.UserAccount;

@Component
public class SuccessionSecurity {

	private static final String RATE_LIMIT_SCOPE = "super-admin-succession-confirmation";

	private final AuthenticatedUserProvider authenticated;
	private final CurrentSessionProvider sessions;
	private final RequestRateLimiter limiter;
	private final PrivilegedConfirmationService proofs;
	private final SuccessionConfirmationStore confirmations;
	private final SessionValidityVerifier sessionValidity;
	private final Clock clock;

	public SuccessionSecurity(AuthenticatedUserProvider authenticated, CurrentSessionProvider sessions,
			RequestRateLimiter limiter, PrivilegedConfirmationService proofs, SuccessionConfirmationStore confirmations,
			SessionValidityVerifier sessionValidity, Clock clock) {
		this.authenticated = authenticated;
		this.sessions = sessions;
		this.limiter = limiter;
		this.proofs = proofs;
		this.confirmations = confirmations;
		this.sessionValidity = sessionValidity;
		this.clock = clock;
	}

	/** Acquire before opening the PostgreSQL transaction, once per password/TOTP submission. */
	public Submission beginSubmission() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Le quota doit être contrôlé avant les verrous PostgreSQL.");
		}
		UserAccount principal = authenticated.getCurrentAccount();
		String sessionHash = activeSessionHash();
		RequestRateLimiter.Decision decision = limiter.acquireForAccount(RATE_LIMIT_SCOPE, principal.getUserID(),
				5, Duration.ofMinutes(10));
		if (!decision.allowed()) {
			throw new RequestRateLimitExceededException(decision.retryAfter());
		}
		return new Submission(principal.getUserID(), principal.getSessionVersion(), sessionHash);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public String issueCreationProof(Submission submission, SuccessionScope.Locked scope, SuccessionPreview preview,
			String password, String totp) {
		UserAccount current = scope.requireParticipant(authenticated.getCurrentAccount());
		if (!current.getUserID().equals(scope.outgoing().getUserID())
				|| scope.eligibility() != SuccessionScope.Eligibility.ELIGIBLE
				|| !preview.tenantID().equals(scope.tenantID()) || !preview.outgoing().userID().equals(current.getUserID())
				|| !preview.successor().userID().equals(scope.successor().getUserID())) {
			throw denied();
		}
		String sessionHash = consume(submission, current);
		proofs.verifyCredentials(current, password, totp);
		return proofs.saveProof(current, sessionHash, Action.SUPER_ADMIN_SUCCESSION_CREATE, preview.confirmationDigest());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void requireCreationProof(UserAccount outgoing, SuccessionPreview preview, String proof) {
		AccountLocks.requireCurrentSession(outgoing, authenticated.getCurrentAccount());
		if (!outgoing.getUserID().equals(preview.outgoing().userID())
				|| !sessionValidity.isValid(outgoing.getUserID(), outgoing.getSessionVersion(), activeSessionHash())) {
			throw denied();
		}
		proofs.require(outgoing, Action.SUPER_ADMIN_SUCCESSION_CREATE, preview.confirmationDigest(), proof);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public SuccessionConfirmation verifyConfirmation(Submission submission, SuccessionOffer offer,
			SuccessionRevision revision, UserAccount current, String password, String totp) {
		Instant now = clock.instant();
		if (!offer.isOpenAt(now) || !offer.hasParticipant(current.getUserID()) || !matchesRevision(offer, revision)) {
			throw denied();
		}
		AccountLocks.requireCurrentSession(current, authenticated.getCurrentAccount());
		String sessionHash = consume(submission, current);
		proofs.verifyCredentials(current, password, totp);
		Instant confirmedAt = clock.instant();
		if (!offer.isOpenAt(confirmedAt)) {
			throw denied();
		}
		return new SuccessionConfirmation(offer.offerID(), current.getUserID(), revision.revision(),
				current.getSessionVersion(), sessionHash, revision.previewDigest(), confirmedAt,
				confirmedAt.plus(Duration.ofMinutes(5)));
	}

	public Optional<SuccessionConfirmation> findValidConfirmation(SuccessionOffer offer, SuccessionRevision revision,
			UserAccount current) {
		Instant now = clock.instant();
		return linkedConfirmation(offer, revision, current)
				.filter(found -> offer.isOpenAt(now) && validAt(found, now))
				.filter(found -> sessionValidity.isValid(current.getUserID(), current.getSessionVersion(),
						found.sessionIDHash()));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Instant authorizeFinalization(SuccessionOffer offer, SuccessionRevision revision, SuccessionScope.Locked scope) {
		String currentSession = activeSessionHash();
		AccountLocks.requireCurrentSession(scope.successor(), authenticated.getCurrentAccount());
		if (offer.status() != OfferStatus.ACCEPTED || scope.eligibility() != SuccessionScope.Eligibility.ELIGIBLE
				|| !offer.tenantID().equals(scope.tenantID())
				|| !offer.outgoingUserID().equals(scope.outgoing().getUserID())
				|| !offer.successorUserID().equals(scope.successor().getUserID())) {
			throw denied();
		}
		SuccessionConfirmation outgoing = linkedConfirmation(offer, revision, scope.outgoing())
				.orElseThrow(SuccessionSecurity::denied);
		SuccessionConfirmation successor = linkedConfirmation(offer, revision, scope.successor())
				.filter(found -> found.sessionIDHash().equals(currentSession)).orElseThrow(SuccessionSecurity::denied);

		Instant authorizedAt = sessionValidity.validateTogether(outgoing.userID(), outgoing.sessionVersion(),
				outgoing.sessionIDHash(), successor.userID(), successor.sessionVersion(), successor.sessionIDHash())
				.orElseThrow(SuccessionSecurity::denied);
		if (!offer.isOpenAt(authorizedAt) || !validAt(outgoing, authorizedAt) || !validAt(successor, authorizedAt)) {
			throw denied();
		}
		return authorizedAt;
	}

	private Optional<SuccessionConfirmation> linkedConfirmation(SuccessionOffer offer, SuccessionRevision revision,
			UserAccount current) {
		if (!offer.status().isOpen() || !matchesRevision(offer, revision) || !offer.hasParticipant(current.getUserID())
				|| !AccountLocks.isCurrentAccount(current, current.getUserID(), current.getSessionVersion())) {
			return Optional.empty();
		}
		return confirmations.findByOfferIDAndUserID(offer.offerID(), revision.revision(), current.getUserID())
				.filter(found -> found.sessionVersion() == current.getSessionVersion()
						&& found.previewDigest().equals(revision.previewDigest()));
	}

	private static boolean matchesRevision(SuccessionOffer offer, SuccessionRevision revision) {
		return offer.isCurrentRevision(revision.revision())
				&& offer.offerID().equals(revision.offerID());
	}

	private static boolean validAt(SuccessionConfirmation confirmation, Instant at) {
		return !confirmation.confirmedAt().isAfter(at) && at.isBefore(confirmation.expiresAt());
	}

	private String consume(Submission submission, UserAccount current) {
		String sessionHash = activeSessionHash();
		if (submission == null || !submission.userID.equals(current.getUserID())
				|| submission.sessionVersion != current.getSessionVersion() || !submission.sessionHash.equals(sessionHash)
				|| !submission.used.compareAndSet(false, true)
				|| !sessionValidity.isValid(current.getUserID(), current.getSessionVersion(), sessionHash)) {
			throw denied();
		}
		return sessionHash;
	}

	public String activeSessionHash() {
		String hash = sessions.getActiveSessionIDHash();
		if (!SessionIDHasher.isValidHash(hash)) {
			throw denied();
		}
		return hash;
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException("Confirmation de succession impossible pour cette session ou cette révision.");
	}

	public static final class Submission {
		private final UUID userID;
		private final long sessionVersion;
		private final String sessionHash;
		private final AtomicBoolean used = new AtomicBoolean();

		private Submission(UUID userID, long sessionVersion, String sessionHash) {
			this.userID = userID;
			this.sessionVersion = sessionVersion;
			this.sessionHash = sessionHash;
		}
	}
}
