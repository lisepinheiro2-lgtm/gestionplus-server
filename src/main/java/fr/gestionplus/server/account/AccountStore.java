package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

import fr.gestionplus.server.auth.UserAccount;

public interface AccountStore {

	Optional<UserAccount> findById(UUID userID);
	
	void createAccount(UserAccount account);

	void disableAccount(UUID userID);
}