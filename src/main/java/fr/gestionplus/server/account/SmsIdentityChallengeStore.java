package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;

public interface SmsIdentityChallengeStore {

	void replaceCurrent(SmsIdentityChallenge newChallenge, Instant revokedAt);

	int recordFailedAttempt(Purpose purpose, UUID challengeID, Instant attemptedAt);

	boolean consumeIfCurrent(Purpose purpose, UUID challengeID, Instant now);

	boolean revokeCurrent(Purpose purpose, UUID challengeID, Instant revokedAt);

	boolean confirmSend(Purpose purpose, UUID challengeID, Instant confirmedAt);

	int revokeUnconfirmedSends(Instant now);

	int countByInvitationAndUser(UUID invitationID, UUID userID);

	Optional<SmsIdentityChallenge> findCurrentByInvitationAndUser(UUID invitationID, UUID userID);

	int countByUserAndPurposeSince(UUID userID, Purpose purpose, Instant since);

	Optional<SmsIdentityChallenge> findCurrentMfaByUser(UUID userID);
}
