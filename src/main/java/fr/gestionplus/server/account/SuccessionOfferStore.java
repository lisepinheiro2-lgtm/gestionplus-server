package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

import fr.gestionplus.server.account.SuccessionOffer.Status;

public interface SuccessionOfferStore {

	void create(SuccessionOffer offer);
	
	Optional<SuccessionOffer> findByIdForUpdate(UUID offerID);
	
	boolean cancel(UUID offerID, Status expectedStatus, Status newStatus);
	
	boolean complete(UUID offerID, Status expectedStatus, Status newStatus);
	
	boolean updateStatus(UUID offerID, Status expectedStatus, Status newStatus);
	
	Optional<SuccessionOffer> findById(UUID offerID);
}
