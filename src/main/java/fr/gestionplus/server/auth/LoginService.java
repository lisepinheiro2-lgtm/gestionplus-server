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
			return rejectAfterDummyVerification(null, AuditReason.UNKNOWN_ACCOUNT, rawPassword);
		}

		UserAccount account = found.get();

		if (account.getStatu() == UserAccountStatus.DISABLED) {
			return rejectAfterDummyVerification(account.getUserID(), AuditReason.DISABLED_ACCOUNT, rawPassword);
		}

		if (account.getStatu() != UserAccountStatus.ACTIVE) {
			return rejectAfterDummyVerification(account.getUserID(), AuditReason.ACCOUNT_NOT_ACTIVE, rawPassword);
		}

		Optional<UserCredential> credential = userCredentialLookup.findByUserId(account.getUserID());

		if (credential.isEmpty() || !account.getUserID().equals(credential.get().getUserId())) {
			return rejectAfterDummyVerification(account.getUserID(), AuditReason.INVALID_CREDENTIALS, rawPassword);
		}

		if (!credentialService.verifyPassword(credential.get(), rawPassword)) {
			return rejectAfterDelay(account.getUserID(), AuditReason.INVALID_CREDENTIALS);
		}

		return new LoginAttempt(account, account.getUserID(), null);
	}

	private LoginAttempt rejectAfterDummyVerification(UUID targetId, AuditReason reason, String rawPassword) {
		credentialService.verifyDummyPassword(rawPassword);
		return rejectAfterDelay(targetId, reason);
	}

	private LoginAttempt rejectAfterDelay(UUID targetId, AuditReason reason) {
		loginDelay.pause();
		return new LoginAttempt(null, targetId, reason);
	}

	public record LoginAttempt(UserAccount account, UUID targetId, AuditReason reason) {
	}
}
