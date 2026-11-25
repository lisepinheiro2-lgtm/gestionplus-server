package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.account.SmsIdentityChallenge.Binding;
import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;
import fr.gestionplus.server.account.SmsIdentityChallengeService.Reservation;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;

@Service
public class ActivationIdentityChallengeService {

	private static final Duration CODE_VALIDITY = Duration.ofMinutes(5);
	private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
	private static final int MAX_SMS_PER_INVITATION = 3;
	private final SmsIdentityChallengeStore challengeStore;
	private final AccountInvitationStore invitationStore;
	private final AccountStore accountStore;
	private final SmsIdentityChallengeService smsChallenges;
	private final Clock clock;

	ActivationIdentityChallengeService(SmsIdentityChallengeStore challengeStore,
			AccountInvitationStore invitationStore, AccountStore accountStore,
			SmsIdentityChallengeService smsChallenges, ObjectProvider<Clock> clocks) {

		this.challengeStore = challengeStore;
		this.invitationStore = invitationStore;
		this.accountStore = accountStore;
		this.smsChallenges = smsChallenges;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	private void refuseIfResendBlocked(UUID invitationID, UUID userID, Instant now) {
		Optional<SmsIdentityChallenge> challenge = challengeStore.findCurrentByInvitationAndUser(invitationID,
				userID);

		if (challenge.isPresent()) {
			SmsIdentityChallenge current = challenge.get();

			if (current.isCurrent(now) && current.createdAt().plus(RESEND_COOLDOWN).isAfter(now)) {
				throw new AccountActivationConflictException();
			}

		}

		if (challengeStore.countByInvitationAndUser(invitationID, userID) >= MAX_SMS_PER_INVITATION) {
			throw new AccountActivationConflictException();
		}

	}

	public void createChallenge(UUID invitationID, String expectedTokenDigest) {

		SmsIdentityChallengeService.requireOutsideTransaction();

		Instant now = clock.instant();

		Invitation invitation = invitationStore.findByID(invitationID).filter(i -> i.tokenDigest() != null)
				.filter(i -> i.completedAt() == null)
				.filter(i -> i.expiresAt() != null).filter(i -> now.isBefore(i.expiresAt()))
				.orElseThrow(AccountActivationConflictException::new);
		requireMatchingToken(invitation, expectedTokenDigest);

		UUID userID = invitation.userID();
		accountStore.findById(userID)
				.filter(a -> a.getStatu() == UserAccountStatus.PENDING_ACTIVATION)
				.orElseThrow(AccountActivationConflictException::new);

		refuseIfResendBlocked(invitationID, invitation.userID(), now);

		smsChallenges.issue(Purpose.ACCOUNT_ACTIVATION, CODE_VALIDITY, () -> {
			UserAccount lockedAccount = accountStore.findByIdForUpdate(userID)
					.filter(a -> a.getStatu() == UserAccountStatus.PENDING_ACTIVATION)
					.orElseThrow(AccountActivationConflictException::new);
			Invitation lockedInvitation = invitationStore.findByIDForUpdate(invitationID)
					.filter(i -> userID.equals(i.userID()) && i.completedAt() == null && i.expiresAt() != null)
					.orElseThrow(AccountActivationConflictException::new);
			Instant lockedNow = clock.instant();
			requireMatchingToken(lockedInvitation, expectedTokenDigest);

			if (!lockedNow.isBefore(lockedInvitation.expiresAt())) {
				throw new AccountActivationConflictException();
			}

			refuseIfResendBlocked(invitationID, userID, lockedNow);
			Binding binding = Binding.forActivation(invitationID, userID, lockedAccount.getPhoneNumber());
			return new Reservation(binding, lockedNow);
		});
	}

	private void requireMatchingToken(Invitation invitation, String expectedTokenDigest) {

		if (invitation.tokenDigest() == null || expectedTokenDigest == null
				|| !MessageDigest.isEqual(invitation.tokenDigest().getBytes(StandardCharsets.US_ASCII),
						expectedTokenDigest.getBytes(StandardCharsets.US_ASCII))) {
			throw AccountInvitationScope.activationDenied();
		}

	}
}
