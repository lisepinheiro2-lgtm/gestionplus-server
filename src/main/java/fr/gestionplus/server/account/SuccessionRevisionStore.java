package fr.gestionplus.server.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SuccessionRevisionStore {

	/** Writes a snapshot in the offer transaction. Open the initial offer or advance it through SuccessionOfferStore. */
	void create(SuccessionRevision revision);

	Optional<SuccessionRevision> find(UUID offerID, long revision);

	/** Returns immutable snapshots in ascending revision order. */
	List<SuccessionRevision> findHistory(UUID offerID);
}
