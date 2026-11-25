package fr.gestionplus.server.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SuccessionConsentStore {

	/** Requires the offer transaction. A repeated or stale consent returns false and preserves prior acceptance. */
	boolean saveIfAbsent(SuccessionConsent consent);

	Optional<SuccessionConsent> find(UUID offerID, long revision, UUID userID);

	List<SuccessionConsent> findByRevision(UUID offerID, long revision);
}
