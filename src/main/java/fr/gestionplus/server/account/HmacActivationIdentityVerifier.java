package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.gestionplus.server.account.SmsIdentityChallenge.Binding;

@Component
public final class HmacActivationIdentityVerifier implements ActivationIdentityVerifier {

	private final SmsIdentityChallengeService challenges;

	public HmacActivationIdentityVerifier(SmsIdentityChallengeService challenges) {
		this.challenges = challenges;
	}

	@Override
	public Optional<UUID> verify(UUID invitationID, UUID userID, String phoneNumber, String proof) {
		if (invitationID == null || userID == null || phoneNumber == null) {
			return Optional.empty();
		}
		return challenges.verify(Binding.forActivation(invitationID, userID, phoneNumber), proof);
	}
}
