package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;

@Service
public class AccountInvitationService {

	private final AccountStore accountStore;
	private final AccountInvitationStore invitationStore;
	private final AccountInvitationDeliveryStore deliveries;
	private final AccountInvitationDelivery deliveryWorker;
	private final InvitationTokenIssuer tokens;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AccountInvitationScope scope;
	private final AccountAccessLifecycle lifecycle;
	private final AuditService auditService;
	private final CredentialService credentialService;
	private final ObjectProvider<ActivationIdentityVerifier> identityVerifiers;
	private final Clock clock;
	private final TransactionTemplate replacementTransaction;

	AccountInvitationService(AccountStore accountStore, AccountInvitationStore invitationStore,
			AccountInvitationDeliveryStore deliveries, AccountInvitationDelivery deliveryWorker,
			InvitationTokenIssuer tokens, AuthenticatedUserProvider authenticatedUserProvider,
			AccountInvitationScope scope,
			AccountAccessLifecycle lifecycle, AuditService auditService, CredentialService credentialService,
			ObjectProvider<ActivationIdentityVerifier> identityVerifiers,
			PlatformTransactionManager transactionManager, ObjectProvider<Clock> clocks) {
		this.accountStore = accountStore;
		this.invitationStore = invitationStore;
		this.deliveries = deliveries;
		this.deliveryWorker = deliveryWorker;
		this.tokens = tokens;
		this.authenticatedUserProvider = authenticatedUserProvider;
		this.scope = scope;
		this.lifecycle = lifecycle;
		this.auditService = auditService;
		this.credentialService = credentialService;
		this.identityVerifiers = identityVerifiers;
		clock = clocks.getIfAvailable(Clock::systemUTC);
		replacementTransaction = new TransactionTemplate(transactionManager);
		replacementTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@AuditedOperation(value = AuditAction.INVITATION_CREATE, account = 0, structure = 1)
	@Transactional
	public Delivery sendInitialInvitation(UUID userID, UUID structureID) {

		UserAccount account = lockPendingAccount(userID, structureID);

		if (invitationStore.findByAccount(userID).isPresent()) {
			throw AccountInvitationScope.denied();
		}

		requireReady(account, structureID);
		return enqueue(account, newInvitation(userID, structureID, false), null, AuditAction.INVITATION_CREATE,
				Map.of());
	}

	@AuditedOperation(value = AuditAction.INVITATION_CREATE, account = 0, structure = 1)
	@Transactional
	public Delivery inviteForReintegration(UUID userID, UUID structureID, Map<UUID, Set<UserRole>> preparedRoles,
			String correctedEmail) {

		Map<UUID, Set<UserRole>> roles = new HashMap<>();
		preparedRoles.forEach((id, requestedRoles) -> roles.put(id, Set.copyOf(requestedRoles)));

		if (!roles.containsKey(structureID)) {
			throw AccountInvitationScope.denied();
		}

		UserAccount account = scope.lockAndAuthorize(userID, structureID, roles);

		if (account.getStatu() != UserAccountStatus.DISABLED) {
			throw AccountInvitationScope.denied();
		}

		scope.requireEmployee(account, structureID);
		accountStore.disableAccount(userID);

		if (correctedEmail != null) {
			updateEmail(account, correctedEmail, structureID);
		}

		return enqueue(accountStore.findById(userID).orElseThrow(), newInvitation(userID, structureID, true),
				null, AuditAction.INVITATION_CREATE, roles);
	}

	@AuditedOperation(value = AuditAction.INVITATION_RESEND, account = 0, structure = 1)
	@Transactional
	public Delivery resendInvitation(UUID userID, UUID structureID) {

		UserAccount account = lockPendingAccount(userID, structureID);
		Invitation current = requireInvitation(userID, structureID);

		if (!isUsable(current, clock.instant())) {
			throw AccountInvitationScope.denied();
		}

		requireReady(account, structureID);
		return enqueue(account, newInvitation(userID, structureID, current.reintegration()),
				current.invitationID(), AuditAction.INVITATION_RESEND, Map.of());
	}

	@AuditedOperation(value = AuditAction.INVITATION_REPLACE, account = 0, structure = 1)
	@Transactional(propagation = Propagation.NEVER)
	public Delivery replaceInvitation(UUID userID, UUID structureID, String correctedEmail) {

		UUID replacementID = replacementTransaction.execute(transaction -> {
			UserAccount account = lockPendingAccount(userID, structureID);
			Invitation current = requireInvitation(userID, structureID);

			if (current.completedAt() != null || (current.expiresAt() != null && !isUsable(current, clock.instant()))) {
				throw AccountInvitationScope.denied();
			}

			deliveries.findPending(userID).ifPresent(request -> deliveries.cancel(request.requestID()));
			updateEmail(account, correctedEmail, structureID);
			Invitation replacement = newInvitation(userID, structureID, current.reintegration());
			invitationStore.save(replacement);
			record(AuditAction.INVITATION_REVOKE, AuditResult.SUCCESS, current);
			return replacement.invitationID();
		});

		return replacementTransaction.execute(transaction -> {
			UserAccount account = lockPendingAccount(userID, structureID);
			Invitation replacement = requireInvitation(userID, structureID);

			if (!replacement.invitationID().equals(replacementID) || replacement.tokenDigest() != null) {
				throw AccountInvitationScope.denied();
			}

			requireReady(account, structureID);
			return enqueue(account, replacement, replacement.invitationID(), AuditAction.INVITATION_REPLACE, Map.of());
		});
	}

	@AuditedOperation(value = AuditAction.INVITATION_REVOKE, account = 0, structure = 1)
	@Transactional
	public void cancelInvitation(UUID userID, UUID structureID) {

		AccountInvitationDeliveryStore.Request pending = deliveries.findPending(userID).orElse(null);
		UserAccount account = scope.lockAndAuthorize(userID, structureID,
				pending == null ? Map.of() : pending.roles());

		if (account.getStatu() != UserAccountStatus.PENDING_ACTIVATION
				&& (account.getStatu() != UserAccountStatus.DISABLED || pending == null
						|| pending.roles().isEmpty())) {
			throw AccountInvitationScope.denied();
		}

		Invitation invitation = invitationStore.findByAccount(userID)
				.filter(current -> structureID.equals(current.structureID()))
				.orElseGet(() -> deliveries.findPending(userID).filter(current -> pending != null
						&& current.requestID().equals(pending.requestID())
						&& structureID.equals(current.structureID()))
						.map(current -> new Invitation(current.invitationID(), userID, structureID,
								null, null, null, current.reintegration()))
						.orElseThrow(AccountInvitationScope::denied));
		lifecycle.disable(account, structureID);
		record(AuditAction.INVITATION_REVOKE, AuditResult.SUCCESS, invitation);
	}

	@AuditedOperation(value = AuditAction.INVITATION_FRAUD_DISABLE, account = 0, structure = 1)
	@Transactional
	public void disableCompromisedActivation(UUID userID, UUID structureID) {

		UserAccount account = scope.lockAndAuthorize(userID, structureID, Map.of());
		Invitation invitation = requireInvitation(userID, structureID);

		if (account.getStatu() != UserAccountStatus.ACTIVE || invitation.completedAt() == null) {
			throw AccountInvitationScope.denied();
		}

		lifecycle.disable(account, structureID);
		record(AuditAction.INVITATION_FRAUD_DISABLE, AuditResult.SUCCESS, invitation);
	}

	@AuditedOperation(value = AuditAction.INVITATION_EXPIRE, invitation = 0)
	@Transactional
	public void expireInvitation(UUID invitationID) {

		invitationStore.findByID(invitationID).ifPresent(candidate -> {

			UserAccount account = accountStore.findByIdForUpdate(candidate.userID()).orElseThrow();

			invitationStore.findByID(invitationID)
					.filter(invitation -> account.getStatu() == UserAccountStatus.PENDING_ACTIVATION)
					.filter(invitation -> invitation.expiresAt() != null
							&& !clock.instant().isBefore(invitation.expiresAt()))
					.ifPresent(invitation -> {
						lifecycle.disable(account, invitation.structureID());
						record(AuditAction.INVITATION_EXPIRE, AuditResult.SUCCESS, invitation);
					});
		});
	}

	@AuditedOperation(value = AuditAction.INVITATION_TOKEN_USE, invitation = 0)
	@Transactional
	public void completeActivation(UUID invitationID, String token, String proof, String userName, String rawPassword) {

		if (invitationID == null || token == null || !token.matches("[A-Za-z0-9_-]{43}") || proof == null
				|| proof.isBlank()) {
			throw AccountInvitationScope.denied();
		}

		Invitation candidate = invitationStore.findByID(invitationID)
				.filter(current -> current.tokenDigest() != null || current.completedAt() != null)
				.orElseThrow(() -> activationUnavailable(invitationID, token));
		UserAccount account = accountStore.findByIdForUpdate(candidate.userID())
				.orElseThrow(AccountInvitationScope::denied);
		Invitation invitation = invitationStore.findByID(invitationID).orElseThrow(AccountInvitationScope::denied);

		if (account.getStatu() != UserAccountStatus.PENDING_ACTIVATION || !isUsable(invitation, clock.instant())
				|| !MessageDigest.isEqual(InvitationTokenIssuer.digest(token).getBytes(StandardCharsets.US_ASCII),
						invitation.tokenDigest().getBytes(StandardCharsets.US_ASCII))) {
			throw AccountInvitationScope.denied();
		}

		requireReady(account, invitation.structureID());
		ActivationIdentityVerifier verifier = identityVerifiers.getIfAvailable();

		if (verifier == null || !verifier.verify(invitationID, account.getUserID(), account.getPhoneNumber(), proof)) {
			throw AccountInvitationScope.denied();
		}

		UserCredential credential = credentialService.createCredential(account.getUserID(), rawPassword);

		if (!isUsable(invitation, clock.instant())) {
			throw AccountInvitationScope.denied();
		}

		accountStore.activateAccount(account.getUserID(), userName, credential);
		deliveries.findPending(account.getUserID()).ifPresent(request -> deliveries.cancel(request.requestID()));
		invitationStore.save(new Invitation(invitationID, account.getUserID(), invitation.structureID(), null, null,
				clock.instant(), invitation.reintegration()));
		record(AuditAction.INVITATION_TOKEN_USE, AuditResult.SUCCESS, invitation);
		record(invitation.reintegration() ? AuditAction.ACCOUNT_REACTIVATE : AuditAction.ACCOUNT_ACTIVATE,
				AuditResult.SUCCESS, invitation);
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

	private UserAccount lockPendingAccount(UUID userID, UUID structureID) {

		UserAccount account = scope.lockAndAuthorize(userID, structureID, Map.of());

		if (account.getStatu() != UserAccountStatus.PENDING_ACTIVATION) {
			throw AccountInvitationScope.denied();
		}

		return account;
	}

	private void requireReady(UserAccount account, UUID structureID) {
		scope.requireEmployee(account, structureID);
		scope.requirePreparedAccess(account, structureID);
	}

	private Invitation requireInvitation(UUID userID, UUID structureID) {
		return invitationStore.findByAccount(userID).filter(invitation -> structureID.equals(invitation.structureID()))
				.orElseThrow(AccountInvitationScope::denied);
	}

	private Invitation newInvitation(UUID userID, UUID structureID, boolean reintegration) {
		return new Invitation(UUID.randomUUID(), userID, structureID, null, null, null, reintegration);
	}

	private Delivery enqueue(UserAccount account, Invitation invitation, UUID previousInvitationID, AuditAction action,
			Map<UUID, Set<UserRole>> roles) {

		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("L’envoi exige une transaction métier.");
		}

		UserAccount actor = authenticatedUserProvider.getCurrentAccount();
		AccountInvitationDeliveryStore.Request pending = deliveries.findPending(account.getUserID()).orElse(null);

		if (pending != null && (!pending.email().equals(account.getEmail())
				|| !pending.structureID().equals(invitation.structureID())
				|| pending.reintegration() != invitation.reintegration()
				|| pending.actorVersion() != actor.getSessionVersion()
				|| !pending.actorID().equals(actor.getUserID())
				|| pending.targetVersion() != account.getSessionVersion()
				|| !Objects.equals(pending.previousInvitationID(), previousInvitationID)
				|| pending.action() != action || !pending.roles().equals(roles))) {
			deliveries.cancel(pending.requestID());
			pending = null;
		}

		if (pending == null) {
			UUID requestID = UUID.randomUUID();
			String tokenDigest = InvitationTokenIssuer.digest(tokens.token(requestID, invitation.invitationID()));
			pending = new AccountInvitationDeliveryStore.Request(requestID, invitation.invitationID(),
					previousInvitationID, account.getUserID(), invitation.structureID(), actor.getUserID(),
					actor.getSessionVersion(), account.getSessionVersion(), account.getEmail(), action,
					invitation.reintegration(), tokenDigest, roles);
			deliveries.enqueue(pending);
		}

		Delivery delivery = new Delivery(pending.requestID());
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				delivery.status = deliveryWorker.deliver(delivery.requestID);
			}
		});
		return delivery;
	}

	private boolean isUsable(Invitation invitation, Instant now) {
		return invitation.tokenDigest() != null && invitation.expiresAt() != null
				&& now.isBefore(invitation.expiresAt());
	}

	private void updateEmail(UserAccount account, String email, UUID structureID) {

		if (email == null || email.isBlank() || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("L’adresse e-mail est invalide.");
		}

		String normalizedEmail = email.strip();

		if (!normalizedEmail.equals(account.getEmail())) {
			accountStore.updateEmail(account.getUserID(), normalizedEmail);
			account.setEmail(normalizedEmail);
			auditService.record(AuditAction.ACCOUNT_CONTACT_UPDATE, AuditResult.SUCCESS,
					accountTargets(account.getUserID(), structureID));
		}

	}

	private Set<AuditTarget> accountTargets(UUID userID, UUID structureID) {
		return Set.of(new AuditTarget(AuditTargetType.ACCOUNT, userID),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
	}

	private void record(AuditAction action, AuditResult result, Invitation invitation) {
		auditService.record(action, result,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, invitation.userID()),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, invitation.structureID()),
						new AuditTarget(AuditTargetType.INVITATION, invitation.invitationID())),
				result == AuditResult.FAILURE ? AuditMetadata.because(AuditReason.OPERATION_FAILED)
						: AuditMetadata.empty());
	}

	public static final class Delivery {
		private final UUID requestID;
		private volatile AccountInvitationDelivery.Status status = AccountInvitationDelivery.Status.PENDING;

		private Delivery(UUID requestID) {
			this.requestID = requestID;
		}

		public UUID requestID() {
			return requestID;
		}

		public boolean sent() {
			return status == AccountInvitationDelivery.Status.SENT;
		}

		public boolean queued() {
			return status == AccountInvitationDelivery.Status.PENDING;
		}
	}
}
