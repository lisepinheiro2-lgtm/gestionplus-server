package fr.gestionplus.server.auth;

import java.util.Optional;

public interface UserAccountLookup {

	Optional<UserAccount> findByUserName(String userName);
}
