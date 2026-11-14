package fr.gestionplus.server.auth;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;

@Service
public class PasswordChangeService {

	private final AuthenticatedUserProvider authenticated;
	private final AccountStore accounts;
	private final UserCredentialLookup credentials;
	private final CredentialService passwords;
	private final AccountMfaService mfa;
	private final SessionRevocationService sessions;
	private final AuditService audit;
	private final TransactionTemplate transaction;

	public PasswordChangeService(AuthenticatedUserProvider authenticated, AccountStore accounts,
			UserCredentialLookup credentials, CredentialService passwords, AccountMfaService mfa,
			SessionRevocationService sessions, AuditService audit,
			PlatformTransactionManager transactionManager) {
		this.authenticated = authenticated;
		this.accounts = accounts;
		this.credentials = credentials;
		this.passwords = passwords;
		this.mfa = mfa;
		this.sessions = sessions;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@AuditedOperation(AuditAction.ACCOUNT_PASSWORD_CHANGE)
	public void changePassword(String currentPassword, String newPassword, String totpCode) {
		UserAccount principal = authenticated.getCurrentAccount();

		passwords.validatePassword(newPassword);

		transaction.executeWithoutResult(status -> {

			UserAccount current = accounts.findByIdForUpdate(principal.getUserID())
					.filter(account -> account.getUserID().equals(principal.getUserID()))
					.filter(account -> account.getStatu() == UserAccountStatus.ACTIVE)
					.filter(account -> account.getSessionVersion() == principal.getSessionVersion())
					.orElseThrow(this::denied);

			UserCredential credential = credentials.findByUserId(current.getUserID())
					.filter(secret -> secret.getUserId().equals(current.getUserID())).orElseThrow(this::denied);

			if (!passwords.verifyPassword(credential, currentPassword)) {
				throw denied();
			}

			AccountMfaService.Verification verification = mfa.verifyForLogin(current, totpCode);

			if (verification != AccountMfaService.Verification.NOT_REQUIRED
					&& verification != AccountMfaService.Verification.VERIFIED) {
				throw denied();
			}

			UserCredential replacement = passwords.encodePassword(current.getUserID(), newPassword);
			accounts.replaceCredential(replacement);
			accounts.advanceSessionVersion(current.getUserID());

			Set<AuditTarget> targets = Set.of(new AuditTarget(AuditTargetType.ACCOUNT, current.getUserID()));
			audit.record(AuditAction.ACCOUNT_PASSWORD_CHANGE, AuditResult.SUCCESS, targets);
			audit.record(AuditAction.AUTH_SESSION_REVOKE, AuditResult.SUCCESS, targets);
			sessions.revokeAllSessionsAfterCommit(current);
		});
	}

	private AccessDeniedException denied() {
		return new AccessDeniedException("Changement de mot de passe impossible.");
	}
}
