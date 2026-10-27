package fr.gestionplus.server.account;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

import fr.gestionplus.server.auth.UserAccountLookup;
import fr.gestionplus.server.auth.UserRole;

public class AccountProvisioningService {

	private final AccountStore store;
	private final PasswordEncoder encoder;
	private final UserAccountLookup accountLookup;

	public AccountProvisioningService(AccountStore store, PasswordEncoder encoder, UserAccountLookup accountLookup) {
		this.store = Objects.requireNonNull(store);
		this.encoder = Objects.requireNonNull(encoder);
		this.accountLookup = Objects.requireNonNull(accountLookup);
	}

	public UUID createAccount(String userName, String rawPassword, Set<UserRole> roles) {

		throw new UnsupportedOperationException(
				"La création directe est désactivée : utiliser le parcours d'invitation.");
	}
}
