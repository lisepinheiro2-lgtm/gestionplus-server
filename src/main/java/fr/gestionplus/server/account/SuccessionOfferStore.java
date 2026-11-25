package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import fr.gestionplus.server.account.SuccessionOffer.ClosureReason;

public interface SuccessionOfferStore {

	/** Requires a transaction. Creates revision 1 atomically and expires any previous overdue offer. */
	void create(SuccessionOffer offer, SuccessionRevision initialRevision);

	Optional<SuccessionOffer> findById(UUID offerID);

	/** Acquires the offer lock after structures, accounts and organization. */
	Optional<SuccessionOffer> findByIdForUpdate(UUID offerID);

	/** Includes closed and historical offers. The caller supplies the authenticated participant. */
	List<SuccessionOffer> findByParticipant(UUID userID);

	/** Finds expiry candidates without locking them. The caller rechecks them under the organization and offer locks. */
	List<SuccessionOffer> findExpiredOffers(Instant cutoff, int limit);

	/** Appends the expected next revision and its explicit ADMIN choices, resets consent status and clears confirmations. */
	boolean advanceRevision(UUID offerID, long expectedRevision, SuccessionRevision next);

	/** Requires an open offer with both current consents. */
	boolean markAccepted(UUID offerID, long expectedRevision, Instant acceptedAt);

	/** Closes an open offer on the expected revision, with expiration taking effect at its original deadline. */
	boolean close(UUID offerID, long expectedRevision, ClosureReason reason, Instant closedAt);

	/** Must share the transfer transaction and use the timestamp from joint session validation. */
	boolean complete(UUID offerID, long expectedRevision, Instant authorizedAt);
}
