package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;

@Service
public class AccountActivationService {

	private final AccountStore accountStore;
	private final AccountInvitationStore invitationStore;
	private final AccountInvitationDeliveryStore deliveries;
	private final AccountInvitationScope scope;
	private final AuditService auditService;
	private final CredentialService credentialService;
	private final ObjectProvider<ActivationIdentityVerifier> identityVerifiers;
	private final Clock clock;
	private final TransactionTemplate activationTransaction;

	AccountActivationService(AccountStore accountStore, AccountInvitationStore invitationStore,
			AccountInvitationDeliveryStore deliveries, AccountInvitationScope scope,
			AuditService auditService, CredentialService credentialService,
			ObjectProvider<ActivationIdentityVerifier> identityVerifiers,
			PlatformTransactionManager transactionManager, ObjectProvider<Clock> clocks) {
		this.accountStore = accountStore;
		this.invitationStore = invitationStore;
		this.deliveries = deliveries;
		this.scope = scope;
		this.auditService = auditService;
		this.credentialService = credentialService;
		this.identityVerifiers = identityVerifiers;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.activationTransaction = new TransactionTemplate(transactionManager);
	}

	@AuditedOperation(value = AuditAction.INVITATION_TOKEN_USE, invitation = 0)
	public void completeActivation(UUID invitationID, String token, String proof, String userName, String rawPassword) {

		if (invitationID == null || token == null || !token.matches("[A-Za-z0-9_-]{43}") || proof == null
				|| proof.isBlank()) {
			throw AccountInvitationScope.denied();
		}

		Invitation claimed = invitationStore.findByID(invitationID)
				.filter(current -> current.tokenDigest() != null || current.completedAt() != null)
				.orElseThrow(() -> activationUnavailable(invitationID, token));

		if (!isUsable(claimed, clock.instant())
				|| !MessageDigest.isEqual(InvitationTokenIssuer.digest(token).getBytes(StandardCharsets.US_ASCII),
						claimed.tokenDigest().getBytes(StandardCharsets.US_ASCII))) {
			throw AccountInvitationScope.denied();
		}

		credentialService.validatePassword(rawPassword);

		activationTransaction.executeWithoutResult(status -> {

			Invitation candidate = invitationStore.findByID(invitationID)
					.filter(current -> current.tokenDigest() != null || current.completedAt() != null)
					.orElseThrow(() -> activationUnavailable(invitationID, token));
			UserAccount account = accountStore.findByIdForUpdate(candidate.userID())
					.orElseThrow(AccountInvitationScope::denied);
			Invitation invitation = invitationStore.findByID(invitationID)
					.orElseThrow(AccountInvitationScope::denied);

			if (account.getStatu() != UserAccountStatus.PENDING_ACTIVATION
					|| !isUsable(invitation, clock.instant())
					|| !MessageDigest.isEqual(InvitationTokenIssuer.digest(token).getBytes(StandardCharsets.US_ASCII),
							invitation.tokenDigest().getBytes(StandardCharsets.US_ASCII))) {
				throw AccountInvitationScope.denied();
			}

			requireReady(account, invitation.structureID());
			ActivationIdentityVerifier verifier = identityVerifiers.getIfAvailable();

			if (verifier == null
					|| !verifier.verify(invitationID, account.getUserID(), account.getPhoneNumber(), proof)) {
				throw AccountInvitationScope.denied();
			}

			UserCredential credential = credentialService.encodePassword(account.getUserID(), rawPassword);

			if (!isUsable(invitation, clock.instant())) {
				throw AccountInvitationScope.denied();
			}

			accountStore.activateAccount(account.getUserID(), userName, credential);
			deliveries.findPending(account.getUserID()).ifPresent(request -> deliveries.cancel(request.requestID()));
			invitationStore.save(new Invitation(invitationID, account.getUserID(), invitation.structureID(), null,
					null, clock.instant(), invitation.reintegration()));
			record(AuditAction.INVITATION_TOKEN_USE, AuditResult.SUCCESS, invitation);
			record(invitation.reintegration() ? AuditAction.ACCOUNT_REACTIVATE : AuditAction.ACCOUNT_ACTIVATE,
					AuditResult.SUCCESS, invitation);
		});
	}

	private RuntimeException activationUnavailable(UUID invitationID, String token) {
		boolean pending = deliveries.findByInvitation(invitationID)
				.filter(request -> MessageDigest.isEqual(
						InvitationTokenIssuer.digest(token).getBytes(StandardCharsets.US_ASCII),
						request.tokenDigest().getBytes(StandardCharsets.US_ASCII)))
				.isPresent();

		if (pending) {
			return new InvitationDeliveryPendingException();
		}

		return AccountInvitationScope.denied();
	}

	private void requireReady(UserAccount account, UUID structureID) {
		scope.requireEmployee(account, structureID);
		scope.requirePreparedAccess(account, structureID);
	}

	private boolean isUsable(Invitation invitation, Instant now) {
		return invitation.tokenDigest() != null && invitation.expiresAt() != null
				&& now.isBefore(invitation.expiresAt());
	}

	private void record(AuditAction action, AuditResult result, Invitation invitation) {
		auditService.record(action, result,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, invitation.userID()),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, invitation.structureID()),
						new AuditTarget(AuditTargetType.INVITATION, invitation.invitationID())),
				result == AuditResult.FAILURE ? AuditMetadata.because(AuditReason.OPERATION_FAILED)
						: AuditMetadata.empty());
	}

}
