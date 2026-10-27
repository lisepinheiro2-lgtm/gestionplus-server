package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class LoginService {

	private final UserAccountLookup userAccountLookup;
	private final UserCredentialLookup userCredentialLookup;
	private final CredentialService credentialService;

	public LoginService(UserAccountLookup userAccountLookup, UserCredentialLookup userCredentialLookup,
			CredentialService credentialService) {

		this.userAccountLookup = Objects.requireNonNull(userAccountLookup);
		this.userCredentialLookup = Objects.requireNonNull(userCredentialLookup);
		this.credentialService = Objects.requireNonNull(credentialService);
	}

	public Optional<UserAccount> authenticate(String userName, String rawPassword) {

		if (userName == null || rawPassword == null || userName.isBlank() || rawPassword.isBlank())
			return Optional.empty();

		Optional<UserAccount> userAccount = userAccountLookup.findByUserName(userName);

		if (userAccount.isEmpty())
			return Optional.empty();

		if (userAccount.get().getStatu() != UserAccountStatus.ACTIVE)
			return Optional.empty();

		Optional<UserCredential> credential = userCredentialLookup.findByUserId(userAccount.get().getUserID());

		if (credential.isEmpty())
			return Optional.empty();

		if (!userAccount.get().getUserID().equals(credential.get().getUserId()))
			return Optional.empty();

		if (!credentialService.verifyPassword(credential.get(), rawPassword))
			return Optional.empty();

		return userAccount;

	}
}
