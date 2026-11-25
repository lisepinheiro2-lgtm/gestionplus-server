package fr.gestionplus.server.account;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.UserAccount;

@Service
public class SuperAdminSuccessionService {

	private final AuthenticatedUserProvider authenticated;
	private final SuccessionScope scope;
	private final SuccessionSecurity security;
	private final SuccessionTransaction transaction;

	private final SuccessionOfferStore offers;
	private final SuccessionRevisionStore revisions;
	private final SuccessionConsentStore consents;
	private final SuccessionConfirmationStore confirmations;
	private final AccountMfaService mfa;
	private final SuccessionTransfer transfer;
	private final Clock clock;

	public SuperAdminSuccessionService(AuthenticatedUserProvider authenticated, SuccessionScope scope,
			SuccessionSecurity security, SuccessionTransaction transaction, SuccessionOfferStore offers,
			SuccessionRevisionStore revisions, SuccessionConsentStore consents, SuccessionConfirmationStore confirmations,
			AccountMfaService mfa, SuccessionTransfer transfer, Clock clock) {
		this.authenticated = authenticated;
		this.scope = scope;
		this.security = security;
		this.transaction = transaction;
		this.offers = offers;
		this.revisions = revisions;
		this.consents = consents;
		this.confirmations = confirmations;
		this.mfa = mfa;
		this.transfer = transfer;
		this.clock = clock;
	}

	@AuditedOperation(value = AuditAction.SUPER_ADMIN_SUCCESSION_PREVIEW, tenant = 0, account = 1)
	@Transactional
	public SuccessionPreview previewSuccession(UUID tenantID, UUID successorID, Set<UUID> adminRolesToTransfer) {
		security.activeSessionHash();
		UserAccount principal = authenticated.getCurrentAccount();
		SuccessionScope.Locked locked = scope.lock(tenantID, principal.getUserID(), successorID);
		locked.requireParticipant(principal);
		return scope.preview(locked, adminRolesToTransfer);
	}

	public String issueSuccessionCreationProof(SuccessionPreview confirmed, String rawPassword, String totpCode) {
		SuccessionSecurity.Submission submission = security.beginSubmission();
		UserAccount principal = authenticated.getCurrentAccount();
		if (confirmed == null || !principal.getUserID().equals(confirmed.outgoing().userID())) {
			throw new AccessDeniedException("Le récapitulatif ne correspond pas au SUPER_ADMIN sortant.");
		}

		return transaction.execute(() -> {
			SuccessionScope.Locked locked = scope.lock(confirmed.tenantID(), principal.getUserID(),
					confirmed.successor().userID());
			SuccessionPreview current = scope.preview(locked, Set.copyOf(confirmed.adminRolesToTransfer()));
			if (!current.equals(confirmed)) {
				throw new AccessDeniedException("Le récapitulatif de création a changé.");
			}
			return security.issueCreationProof(submission, locked, current, rawPassword, totpCode);
		});
	}

	public SuccessionResult refreshSuccessionPreview(UUID offerID) {
		throw cycleUnavailable();
	}

	public SuccessionResult createSuccessionOffer(SuccessionPreview confirmed, String proof) {
		throw cycleUnavailable();
	}

	public SuccessionResult acceptSuccessionOffer(UUID offerID, long presentedRevision) {
		throw cycleUnavailable();
	}

	public SuccessionResult confirmSuccession(UUID offerID, long presentedRevision, boolean acceptRevision,
			String rawPassword, String totpCode) {
		throw cycleUnavailable();
	}

	public SuccessionResult withdrawSuccessionOffer(UUID offerID) {
		throw cycleUnavailable();
	}

	public SuccessionResult cancelSuccessionOffer(UUID offerID) {
		throw cycleUnavailable();
	}

	public SuccessionResult finalizeSuccession(UUID offerID, long presentedRevision) {
		throw cycleUnavailable();
	}

	public SuccessionResult.Status getSuccessionStatus(UUID offerID) {
		throw cycleUnavailable();
	}

	public SuccessionResult updateAdminChoices(UUID offerID, long presentedRevision, Set<UUID> adminRolesToTransfer) {
		throw cycleUnavailable();
	}

	private static AccessDeniedException cycleUnavailable() {
		return new AccessDeniedException("Le cycle de succession avec révisions n’est pas encore disponible.");
	}
}
