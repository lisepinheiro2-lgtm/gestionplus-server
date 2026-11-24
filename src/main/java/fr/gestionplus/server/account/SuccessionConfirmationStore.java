package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

public interface SuccessionConfirmationStore {

	void replaceCurrent(SuccessionConfirmation confirmation);

	Optional<SuccessionConfirmation> findByOfferIDAndUserID(UUID offerID, UUID userID);

	void deleteByOfferID(UUID offerID);

}
