package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserCredential;

public interface AccountStore {

	Optional<UserAccount> findById(UUID userID);

	Optional<UserAccount> findByIdForUpdate(UUID userID);

	Optional<UserAccount> findByEmployeeID(UUID employeeID);

	void createAccount(UserAccount account);

	void disableAccount(UUID userID);

	void advanceSessionVersion(UUID userID);

	void setPendingActivation(UUID userID);

	void updateEmail(UUID userID, String email);

	void activateAccount(UUID userID, String userName, UserCredential credential);

	void replaceCredential(UserCredential credential);
}
