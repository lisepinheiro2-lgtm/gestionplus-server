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
	private final LoginDelay loginDelay;

	public LoginService(UserAccountLookup userAccountLookup, UserCredentialLookup userCredentialLookup,
			CredentialService credentialService, LoginDelay loginDelay) {

		this.userAccountLookup = Objects.requireNonNull(userAccountLookup);
		this.userCredentialLookup = Objects.requireNonNull(userCredentialLookup);
		this.credentialService = Objects.requireNonNull(credentialService);
		this.loginDelay = Objects.requireNonNull(loginDelay);
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
			return rejectAfterDelay(null, AuditReason.UNKNOWN_ACCOUNT);
		}

		UserAccount account = found.get();

		if (account.getStatu() == UserAccountStatus.DISABLED) {
			return rejectAfterDelay(account.getUserID(), AuditReason.DISABLED_ACCOUNT);
		}

		if (account.getStatu() != UserAccountStatus.ACTIVE) {
			return rejectAfterDelay(account.getUserID(), AuditReason.ACCOUNT_NOT_ACTIVE);
		}

		Optional<UserCredential> credential = userCredentialLookup.findByUserId(account.getUserID());

		if (credential.isEmpty() || !account.getUserID().equals(credential.get().getUserId())) {
			return rejectAfterDelay(account.getUserID(), AuditReason.INVALID_CREDENTIALS);
		}

		if (!credentialService.verifyPassword(credential.get(), rawPassword)) {
			return new LoginAttempt(null, account.getUserID(), AuditReason.INVALID_CREDENTIALS);
		}

		return new LoginAttempt(account, account.getUserID(), null);
	}

	private LoginAttempt rejectAfterDelay(UUID targetId, AuditReason reason) {
		loginDelay.pause();
		return new LoginAttempt(null, targetId, reason);
	}

	public record LoginAttempt(UserAccount account, UUID targetId, AuditReason reason) {
	}
}
