package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import fr.gestionplus.server.account.SuccessionOffer.OfferStatus;

public interface SuccessionOfferStore {

	void create(SuccessionOffer offer);
	
	Optional<SuccessionOffer> findByIdForUpdate(UUID offerID);
	
	boolean cancel(UUID offerID, OfferStatus expectedStatus, OfferStatus newStatus);
	
	boolean updateStatus(UUID offerID, OfferStatus expectedStatus, OfferStatus newStatus);
	
	boolean complete(UUID offerID, Instant authorizedAt);
	
	Optional<SuccessionOffer> findById(UUID offerID);
}
