package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;

@Service
public class AccountActivationService {

	private final AccountStore accountStore;
	private final AccountInvitationStore invitationStore;
	private final ActivationIdentityChallengeStore challengeStore;
	private final AccountInvitationDeliveryStore deliveries;
	private final AccountInvitationScope scope;
	private final AuditService auditService;
	private final CredentialService credentialService;
	private final ActivationIdentityChallengeService challengeService;
	private final ObjectProvider<ActivationIdentityVerifier> identityVerifiers;
	private final Clock clock;
	private final TransactionTemplate activationTransaction;
	private final AuthorizationService authorizationService;
	private final AccountMfaService accountMfaService;

	AccountActivationService(AccountStore accountStore,
			AccountInvitationStore invitationStore,
			ActivationIdentityChallengeStore challengeStore,
			AccountInvitationDeliveryStore deliveries,
			AccountInvitationScope scope,
			AuditService auditService,
			CredentialService credentialService, ActivationIdentityChallengeService challengeService,
			ObjectProvider<ActivationIdentityVerifier> identityVerifiers,
			PlatformTransactionManager transactionManager,
			ObjectProvider<Clock> clocks, AuthorizationService authorizationService,
			AccountMfaService accountMfaService) {

		this.accountStore = accountStore;
		this.invitationStore = invitationStore;
		this.challengeStore = challengeStore;
		this.deliveries = deliveries;
		this.scope = scope;
		this.auditService = auditService;
		this.credentialService = credentialService;
		this.challengeService = challengeService;
		this.identityVerifiers = identityVerifiers;
		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.accountMfaService = Objects.requireNonNull(accountMfaService);
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.activationTransaction = new TransactionTemplate(transactionManager);

	}

	@AuditedOperation(value = AuditAction.INVITATION_SMS_REQUEST, invitation = 0)
	public void requestActivationChallenge(UUID invitationID, String token) {

		if (invitationID == null || !isValidToken(token)) {
			throw new IllegalArgumentException("Requête d'activation malformée.");
		}

		Invitation invitation = requireValidInvitation(invitationID, token);

		challengeService.createChallenge(invitationID, invitation.tokenDigest());
	}

	@AuditedOperation(value = AuditAction.INVITATION_TOKEN_USE, invitation = 0)
	public Optional<String> completeActivation(UUID invitationID, String token,
			String proof, String userName, String rawPassword) {

		validateRequest(invitationID, token, proof);

		Invitation invitation = requireValidInvitation(invitationID, token);

		credentialService.validatePassword(rawPassword);

		UUID challengeID = verifyIdentity(invitation, proof);

		return activationTransaction.execute(status -> activateInTransaction(invitationID, token, challengeID,
				userName, rawPassword));

	}

	private void validateRequest(UUID invitationID, String token, String proof) {

		if (invitationID == null
				|| !isValidToken(token)
				|| proof == null
				|| proof.isBlank()) {
			throw new IllegalArgumentException("Requête d'activation malformée.");
		}

	}

	private boolean isValidToken(String token) {

		return token != null && token.matches("[A-Za-z0-9_-]{43}");
	}

	private Invitation requireValidInvitation(UUID invitationID, String token) {

		Invitation invitation = invitationStore.findByID(invitationID)
				.filter(current -> current.tokenDigest() != null
						|| current.completedAt() != null)
				.orElseThrow(() -> activationUnavailable(invitationID, token));

		if (!isUsable(invitation, clock.instant())
				|| !tokenMatches(invitation, token)) {
			throw AccountInvitationScope.activationDenied();
		}

		return invitation;
	}

	private UUID verifyIdentity(Invitation invitation, String proof) {

		UserAccount account = accountStore.findById(invitation.userID())
				.filter(current -> current.getStatu() == UserAccountStatus.PENDING_ACTIVATION)
				.orElseThrow(AccountInvitationScope::activationDenied);

		ActivationIdentityVerifier verifier = identityVerifiers.getIfAvailable();

		if (verifier == null) {
			throw AccountInvitationScope.activationDenied();
		}

		Optional<UUID> verifiedChallenge = verifier.verify(
				invitation.invitationID(),
				account.getUserID(),
				account.getPhoneNumber(),
				proof);

		if (verifiedChallenge == null || verifiedChallenge.isEmpty()) {
			throw AccountInvitationScope.activationDenied();
		}

		return verifiedChallenge.get();
	}

	private Optional<String> activateInTransaction(UUID invitationID, String token,
			UUID challengeID, String userName, String rawPassword) {

		Invitation candidate = invitationStore.findByID(invitationID)
				.filter(current -> current.tokenDigest() != null
						|| current.completedAt() != null)
				.orElseThrow(() -> activationUnavailable(invitationID, token));

		UserAccount account = accountStore.findByIdForUpdate(candidate.userID())
				.orElseThrow(AccountInvitationScope::activationDenied);

		Invitation invitation = invitationStore.findByID(invitationID)
				.orElseThrow(AccountInvitationScope::activationDenied);

		if (account.getStatu() != UserAccountStatus.PENDING_ACTIVATION
				|| !isUsable(invitation, clock.instant())
				|| !tokenMatches(invitation, token)) {
			throw AccountInvitationScope.activationDenied();
		}

		scope.requireEmployee(account, invitation.structureID());
		scope.requirePreparedAccess(account, invitation.structureID());

		ActivationIdentityChallenge challenge = challengeStore
				.findCurrentByInvitationAndUser(invitationID, account.getUserID())
				.orElseThrow(AccountInvitationScope::activationDenied);

		if (!challenge.challengeID().equals(challengeID)
				|| !Objects.equals(challenge.phoneNumber(), account.getPhoneNumber())
				|| !challenge.isUsable(clock.instant())) {
			throw AccountInvitationScope.activationDenied();
		}

		UserCredential credential = credentialService
				.encodePassword(account.getUserID(), rawPassword);

		if (!isUsable(invitation, clock.instant())
				|| !challengeStore.consumeIfCurrent(challengeID, clock.instant())) {
			throw AccountInvitationScope.activationDenied();
		}

		accountStore.activateAccount(account.getUserID(), userName, credential);

		deliveries.findPending(account.getUserID())
				.ifPresent(request -> deliveries.cancel(request.requestID()));

		invitationStore.save(new Invitation(
				invitationID,
				account.getUserID(),
				invitation.structureID(),
				null,
				null,
				clock.instant(),
				invitation.reintegration()));

		record(AuditAction.INVITATION_TOKEN_USE, AuditResult.SUCCESS, invitation);

		record(invitation.reintegration()
				? AuditAction.ACCOUNT_REACTIVATE
				: AuditAction.ACCOUNT_ACTIVATE,
				AuditResult.SUCCESS, invitation);

		if (authorizationService.requiresMfa(account.getUserID())) {
			return Optional
					.of(accountMfaService.issueEnrollmentProof(account.getUserID(), account.getSessionVersion()));
		}

		return Optional.empty();
	}

	private boolean tokenMatches(Invitation invitation, String token) {

		return invitation.tokenDigest() != null
				&& MessageDigest.isEqual(
						InvitationTokenIssuer.digest(token)
								.getBytes(StandardCharsets.US_ASCII),
						invitation.tokenDigest()
								.getBytes(StandardCharsets.US_ASCII));
	}

	private RuntimeException activationUnavailable(UUID invitationID, String token) {

		boolean pending = deliveries.findByInvitation(invitationID)
				.filter(request -> MessageDigest.isEqual(
						InvitationTokenIssuer.digest(token)
								.getBytes(StandardCharsets.US_ASCII),
						request.tokenDigest()
								.getBytes(StandardCharsets.US_ASCII)))
				.isPresent();

		if (pending) {
			return new InvitationDeliveryPendingException();
		}

		return AccountInvitationScope.activationDenied();
	}

	private boolean isUsable(Invitation invitation, Instant now) {

		return invitation.tokenDigest() != null
				&& invitation.expiresAt() != null
				&& now.isBefore(invitation.expiresAt());
	}

	private void record(AuditAction action, AuditResult result,
			Invitation invitation) {

		auditService.record(action, result,
				Set.of(
						new AuditTarget(
								AuditTargetType.ACCOUNT,
								invitation.userID()),
						new AuditTarget(
								AuditTargetType.ORGANIZATIONAL_STRUCTURE,
								invitation.structureID()),
						new AuditTarget(
								AuditTargetType.INVITATION,
								invitation.invitationID())),
				result == AuditResult.FAILURE
						? AuditMetadata.because(AuditReason.OPERATION_FAILED)
						: AuditMetadata.empty());
	}
}
