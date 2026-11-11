package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.gestionplus.server.audit.AuditReason;

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
		return Optional.ofNullable(evaluate(userName, rawPassword).account());
	}

	public LoginAttempt evaluate(String userName, String rawPassword) {

		if (userName == null || rawPassword == null || userName.isBlank() || rawPassword.isBlank()) {
			return new LoginAttempt(null, null, AuditReason.INVALID_CREDENTIALS);
		}

		Optional<UserAccount> found = userAccountLookup.findByUserName(userName);

		if (found.isEmpty()) {
			return rejectAfterDummyCheck(rawPassword, null, AuditReason.UNKNOWN_ACCOUNT);
		}

		UserAccount account = found.get();

		if (account.getStatu() == UserAccountStatus.DISABLED) {
			return rejectAfterDummyCheck(rawPassword, account.getUserID(), AuditReason.DISABLED_ACCOUNT);
		}

		if (account.getStatu() != UserAccountStatus.ACTIVE) {
			return rejectAfterDummyCheck(rawPassword, account.getUserID(), AuditReason.ACCOUNT_NOT_ACTIVE);
		}

		Optional<UserCredential> credential = userCredentialLookup.findByUserId(account.getUserID());

		if (credential.isEmpty() || !account.getUserID().equals(credential.get().getUserId())) {
			return rejectAfterDummyCheck(rawPassword, account.getUserID(), AuditReason.INVALID_CREDENTIALS);
		}

		if (!credentialService.verifyPassword(credential.get(), rawPassword)) {
			return new LoginAttempt(null, account.getUserID(), AuditReason.INVALID_CREDENTIALS);
		}

		return new LoginAttempt(account, account.getUserID(), null);
	}

	private LoginAttempt rejectAfterDummyCheck(String rawPassword, UUID targetId, AuditReason reason) {
		credentialService.verifyDummyPassword(rawPassword);
		return new LoginAttempt(null, targetId, reason);
	}

	public record LoginAttempt(UserAccount account, UUID targetId, AuditReason reason) {
	}
}
