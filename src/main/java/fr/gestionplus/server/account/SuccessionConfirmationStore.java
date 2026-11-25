package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

public interface SuccessionConfirmationStore {

	/** Writes only for an open current revision and a consenting participant, in the offer transaction. */
	void replaceCurrent(SuccessionConfirmation confirmation);

	Optional<SuccessionConfirmation> findByOfferIDAndUserID(UUID offerID, long revision, UUID userID);

	void deleteByOfferID(UUID offerID);
}
