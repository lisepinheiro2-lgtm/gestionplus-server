package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.SmsIdentityChallenge.Binding;
import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;
import fr.gestionplus.server.account.SmsIdentityChallengeStore.Issuance;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.RequestRateLimitExceededException;
import fr.gestionplus.server.auth.SessionIDHasher;
import fr.gestionplus.server.auth.UserAccount;

@Service
public class SmsIdentityChallengeService {

	private static final Duration SEND_RECOVERY_DELAY = Duration.ofMinutes(2);
	private final SmsIdentityChallengeStore challengeStore;
	private final AccountStore accountStore;
	private final AuthenticatedUserProvider authenticated;
	private final CurrentSessionProvider sessions;
	private final ActivationCodeGenerator codeGenerator;
	private final ActivationCodeDigester codeDigester;
	private final SmsMessageSender messageSender;
	private final Clock clock;
	private final TransactionTemplate transaction;

	SmsIdentityChallengeService(SmsIdentityChallengeStore challengeStore, AccountStore accountStore,
			AuthenticatedUserProvider authenticated, CurrentSessionProvider sessions,
			ActivationCodeGenerator codeGenerator,
			ActivationCodeDigester codeDigester, SmsMessageSender messageSender, ObjectProvider<Clock> clocks,
			PlatformTransactionManager transactionManager) {
		this.challengeStore = challengeStore;
		this.accountStore = accountStore;
		this.authenticated = authenticated;
		this.sessions = sessions;
		this.codeGenerator = codeGenerator;
		this.codeDigester = codeDigester;
		this.messageSender = messageSender;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public void createMfaChallenge(Duration codeValidity, int maxChallenges,
			Duration window, Duration cooldown, Consumer<UserAccount> authorize) {
		if (maxChallenges <= 0 || window.isZero() || window.isNegative()
				|| cooldown.isZero() || cooldown.isNegative()) {
			throw new IllegalArgumentException("La limite de création des défis SMS est invalide.");
		}

		issue(Purpose.MFA_ENROLLMENT, codeValidity, () -> {
			UserAccount current = lockCurrentAccount(authenticated.getCurrentAccount());
			authorize.accept(current);
			Binding binding = Binding.forMfaEnrollment(current.getUserID(), current.getPhoneNumber(),
					requireSessionIDHash(), current.getSessionVersion());
			Instant now = clock.instant();
			enforceMfaIssuanceLimit(current.getUserID(), now, maxChallenges, window, cooldown);
			return new Reservation(binding, now);
		});
	}

	private void enforceMfaIssuanceLimit(UUID userID, Instant now, int maxChallenges, Duration window,
			Duration cooldown) {
		Duration cooldownHistory = SEND_RECOVERY_DELAY.plus(cooldown);
		Duration history = window.compareTo(cooldownHistory) >= 0 ? window : cooldownHistory;
		List<Issuance> recent = challengeStore.findRecentMfaIssuances(userID, now.minus(history), maxChallenges);
		Instant next = recent.size() == maxChallenges ? recent.getLast().createdAt().plus(window) : now;
		for (Issuance issuance : recent) {
			Instant cooldownEnd = issuance.sentAt().plus(cooldown);
			if (cooldownEnd.isAfter(next)) {
				next = cooldownEnd;
			}
			if (issuance.pendingUntil() != null && issuance.pendingUntil().isAfter(next)) {
				next = issuance.pendingUntil();
			}
		}
		if (next.isAfter(now)) {
			throw new RequestRateLimitExceededException(Duration.between(now, next));
		}
	}

	public Optional<VerifiedMfaChallenge> verifyMfaChallenge(String proof) {
		requireOutsideTransaction();


		UserAccount principal = authenticated.getCurrentAccount();
		String sessionIDHash = requireSessionIDHash();
		UserAccount current = accountStore.findById(principal.getUserID())
				.filter(account -> matchesPrincipal(account, principal))
				.orElseThrow(SmsIdentityChallengeService::staleAccount);
		Binding binding = Binding.forMfaEnrollment(current.getUserID(), current.getPhoneNumber(),
				sessionIDHash, current.getSessionVersion());
		String candidate = proof != null && proof.matches("[0-9]{6}") ? proof : null;
		return verify(binding, candidate).map(challengeID -> new VerifiedMfaChallenge(binding, challengeID));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UserAccount> consumeMfaChallenge(VerifiedMfaChallenge verified) {
		UserAccount current = lockCurrentAccount(authenticated.getCurrentAccount());
		Binding binding = Binding.forMfaEnrollment(current.getUserID(), current.getPhoneNumber(),
				requireSessionIDHash(), current.getSessionVersion());
		if (!verified.binding.equals(binding)
				|| findCurrent(binding).filter(challenge -> challenge.challengeID().equals(verified.challengeID)).isEmpty()
				|| !challengeStore.consumeIfCurrent(binding.purpose(), verified.challengeID, clock.instant())) {
			return Optional.empty();
		}

		return Optional.of(current);
	}

	private String requireSessionIDHash() {
		String sessionIDHash = sessions.getActiveSessionIDHash();

		if (!SessionIDHasher.isValidHash(sessionIDHash)) {
			throw new AuditedAccessDeniedException("Une session HTTP active est requise.", AuditReason.STALE_SESSION);
		}

		return sessionIDHash;
	}

	private UserAccount lockCurrentAccount(UserAccount principal) {
		return accountStore.findByIdForUpdate(principal.getUserID())
				.filter(current -> matchesPrincipal(current, principal))
				.orElseThrow(SmsIdentityChallengeService::staleAccount);
	}

	private static boolean matchesPrincipal(UserAccount current, UserAccount principal) {
		return AccountLocks.isCurrentAccount(current, principal.getUserID(), principal.getSessionVersion());
	}

	private static AccessDeniedException staleAccount() {
		return new AuditedAccessDeniedException("Une nouvelle connexion est requise.", AuditReason.STALE_SESSION);
	}

	static void requireOutsideTransaction() {

		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalTransactionStateException("Cette opération SMS doit être appelée hors transaction.");
		}

	}

	void issue(Purpose purpose, Duration codeValidity, Supplier<Reservation> authorize) {
		requireOutsideTransaction();

		if (codeValidity.isZero() || codeValidity.isNegative()) {
			throw new IllegalArgumentException("La durée de validité du défi SMS doit être positive.");
		}

		UUID challengeID = UUID.randomUUID();
		String code = codeGenerator.generate();
		String codeDigest = codeDigester.digest(purpose, challengeID, code);
		SmsIdentityChallenge created = Objects.requireNonNull(transaction.execute(status -> {
			Reservation reservation = authorize.get();
			Binding binding = reservation.binding();
			if (binding.purpose() != purpose) {
				throw new IllegalArgumentException("La finalité du défi SMS est incohérente.");
			}

			Instant now = reservation.authorizedAt();
			SmsIdentityChallenge challenge = new SmsIdentityChallenge(challengeID, binding, codeDigest, 0,
					now, now.plus(codeValidity), null, null, null, now.plus(SEND_RECOVERY_DELAY));
			challengeStore.replaceCurrent(challenge, now);
			return challenge;
		}));

		sendAndConfirm(created, code);
	}

	private void sendAndConfirm(SmsIdentityChallenge challenge, String code) {
		Purpose purpose = challenge.binding().purpose();
		UUID challengeID = challenge.challengeID();

		try {
			if (purpose == Purpose.MFA_ENROLLMENT) {
				requireCurrentMfaDestination(challenge);
			}
			messageSender.send(purpose, challenge.binding().phoneNumber(), code);

			if (!challengeStore.confirmSend(purpose, challengeID, clock.instant())) {
				throw new ActivationMessageDeliveryException(purpose);
			}

		} catch (RuntimeException e) {

			try {
				challengeStore.revokeCurrent(purpose, challengeID, clock.instant());
			} catch (RuntimeException compensationFailure) {

				if (compensationFailure != e) {
					e.addSuppressed(compensationFailure);
				}

			}

			throw e;
		}

	}

	private void requireCurrentMfaDestination(SmsIdentityChallenge challenge) {
		Binding binding = challenge.binding();
		if (!binding.sessionIDHash().equals(requireSessionIDHash())) {
			throw staleAccount();
		}
		accountStore.findById(binding.userID())
				.filter(account -> AccountLocks.isCurrentAccount(account, binding.userID(), binding.sessionVersion()))
				.filter(account -> account.isPhoneVerified() && account.getPhoneNumber().equals(binding.phoneNumber()))
				.orElseThrow(SmsIdentityChallengeService::staleAccount);
		if (!clock.instant().isBefore(challenge.sendRecoveryAt())) {
			throw new ActivationMessageDeliveryException(binding.purpose());
		}
	}

	private Optional<SmsIdentityChallenge> findCurrent(Binding binding) {
		Optional<SmsIdentityChallenge> current = switch (binding.purpose()) {
		case ACCOUNT_ACTIVATION ->
			challengeStore.findCurrentByInvitationAndUser(binding.invitationID(), binding.userID());
		case MFA_ENROLLMENT -> challengeStore.findCurrentMfaByUser(binding.userID());
		};
		return current.filter(challenge -> challenge.binding().equals(binding));
	}

	Optional<UUID> verify(Binding binding, String proof) {
		Instant now = clock.instant();
		Optional<SmsIdentityChallenge> current = findCurrent(binding).filter(challenge -> challenge.isUsable(now));

		if (current.isEmpty()) {
			return Optional.empty();
		}

		SmsIdentityChallenge challenge = current.get();
		UUID challengeID = challenge.challengeID();
		String candidate = codeDigester.digest(binding.purpose(), challengeID, proof);

		if (MessageDigest.isEqual(challenge.codeDigest().getBytes(StandardCharsets.US_ASCII),
				candidate.getBytes(StandardCharsets.US_ASCII))) {
			return Optional.of(challengeID);
		}

		challengeStore.recordFailedAttempt(binding.purpose(), challengeID, now);
		return Optional.empty();
	}

	record Reservation(Binding binding, Instant authorizedAt) {
	}

	public static final class VerifiedMfaChallenge {
		private final Binding binding;
		private final UUID challengeID;

		private VerifiedMfaChallenge(Binding binding, UUID challengeID) {
			this.binding = binding;
			this.challengeID = challengeID;
		}

		public String sessionIDHash() {
			return binding.sessionIDHash();
		}
	}
}
