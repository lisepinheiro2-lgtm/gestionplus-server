package fr.gestionplus.server.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;

@Service
public class ActivationIdentityChallengeService {

	private static final Duration CODE_VALIDITY = Duration.ofMinutes(5);
	private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
	private static final int MAX_SMS_PER_INVITATION = 3;
	private final ActivationIdentityChallengeStore challengeStore;
	private final AccountInvitationStore invitationStore;
	private final AccountStore accountStore;
	private final ActivationCodeGenerator codeGenerator;
	private final ActivationCodeDigester codeDigester;
	private final ActivationMessageSender messageSender;
	private final Clock clock;
	private final TransactionTemplate activationTransaction;

	ActivationIdentityChallengeService(ActivationIdentityChallengeStore challengeStore,
			AccountInvitationStore invitationStore, AccountStore accountStore, ActivationCodeGenerator codeGenerator,
			ActivationCodeDigester codeDigester, ActivationMessageSender messageSender, ObjectProvider<Clock> clocks,
			PlatformTransactionManager transactionManager) {

		this.challengeStore = challengeStore;
		this.invitationStore = invitationStore;
		this.accountStore = accountStore;
		this.codeGenerator = codeGenerator;
		this.codeDigester = codeDigester;
		this.messageSender = messageSender;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.activationTransaction = new TransactionTemplate(transactionManager);
	}

	private void refuseIfResendBlocked(UUID invitationID, UUID userID, Instant now) {
		Optional<ActivationIdentityChallenge> challenge = challengeStore.findCurrentByInvitationAndUser(invitationID,
				userID);

		if (challenge.isPresent()) {
			ActivationIdentityChallenge current = challenge.get();

			if (current.isUsable(now) && current.createdAt().plus(RESEND_COOLDOWN).isAfter(now)) {
				throw new AccountActivationConflictException();
			}

		}

		if (challengeStore.countByInvitationAndUser(invitationID, userID) >= MAX_SMS_PER_INVITATION) {
			throw new AccountActivationConflictException();
		}

	}

	public void createChallenge(UUID invitationID) {

		Instant now = clock.instant();

		Invitation invitation = invitationStore.findByID(invitationID).filter(i -> i.tokenDigest() != null)
				.filter(i -> i.completedAt() == null)
				.filter(i -> i.expiresAt() != null).filter(i -> now.isBefore(i.expiresAt()))
				.orElseThrow(AccountActivationConflictException::new);

		UUID userID = invitation.userID();
		UserAccount account = accountStore.findById(userID)
				.filter(a -> a.getStatu() == UserAccountStatus.PENDING_ACTIVATION)
				.orElseThrow(AccountActivationConflictException::new);

		refuseIfResendBlocked(invitationID, invitation.userID(), now);

		UUID challengeID = UUID.randomUUID();
		String code = codeGenerator.generate();
		String codeDigest = codeDigester.digest(challengeID, code);

		ActivationIdentityChallenge newChallenge = new ActivationIdentityChallenge(challengeID, userID,
				account.getPhoneNumber(), invitationID, codeDigest, 0, now, now.plus(CODE_VALIDITY), null,
				null);

		activationTransaction.executeWithoutResult(status -> {
			accountStore.findByIdForUpdate(invitation.userID())
					.filter(a -> a.getStatu() == UserAccountStatus.PENDING_ACTIVATION)
					.orElseThrow(AccountActivationConflictException::new);
			refuseIfResendBlocked(invitationID, invitation.userID(), now);
			challengeStore.replaceCurrent(newChallenge, now);
		});

		try {

			messageSender.send(account.getPhoneNumber(), code);

		} catch (RuntimeException e) {
			challengeStore.revokeCurrent(challengeID, now);
			throw e;
		}

	}
}
