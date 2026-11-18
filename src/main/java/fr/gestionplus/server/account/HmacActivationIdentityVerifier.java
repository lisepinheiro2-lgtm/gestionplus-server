package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;

@Component
public final class HmacActivationIdentityVerifier implements ActivationIdentityVerifier {

	private final ActivationIdentityChallengeStore challengeStore;
	private final ActivationCodeDigester codeDigester;
	private final Clock clock;

	public HmacActivationIdentityVerifier(ActivationIdentityChallengeStore challengeStore,
			ActivationCodeDigester codeDigester, ObjectProvider<Clock> clocks) {
		this.challengeStore = challengeStore;
		this.codeDigester = codeDigester;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Override
	public Optional<UUID> verify(UUID invitationID, UUID userID, String phoneNumber, String proof) {

		Instant now = clock.instant();
		Optional<ActivationIdentityChallenge> optionalChallenge = challengeStore
				.findCurrentByInvitationAndUser(invitationID, userID);

		if (optionalChallenge.isEmpty()) {
			return Optional.empty();
		}

		ActivationIdentityChallenge challenge = optionalChallenge.get();

		if (!challenge.phoneNumber().equals(phoneNumber) || !challenge.isUsable(now)) {
			return Optional.empty();
		}

		UUID challengeID = challenge.challengeID();
		String codeDigest = challenge.codeDigest();

		String candidate = codeDigester.digest(challengeID, proof);

		if (MessageDigest.isEqual(codeDigest.getBytes(StandardCharsets.US_ASCII),
				candidate.getBytes(StandardCharsets.US_ASCII))) {
			return Optional.of(challengeID);
		}

		challengeStore.recordFailedAttempt(challengeID, now);
		return Optional.empty();
	}
}
