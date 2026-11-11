package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Service
public class AccountInvitationService {

	private static final Duration INVITATION_VALIDITY = Duration.ofDays(7);

	private final SecureRandom random = new SecureRandom();
	private final AccountStore accountStore;
	private final AccountInvitationStore invitationStore;
	private final AccountInvitationSender sender;
	private final AccountInvitationScope scope;
	private final StructureMembershipStore membershipStore;
	private final AccountAccessLifecycle lifecycle;
	private final AuditService auditService;
	private final CredentialService credentialService;
	private final ObjectProvider<ActivationIdentityVerifier> identityVerifiers;
	private final TransactionTemplate replacementTransaction;

	AccountInvitationService(AccountStore accountStore, AccountInvitationStore invitationStore,
			AccountInvitationSender sender, AccountInvitationScope scope, StructureMembershipStore membershipStore,
			AccountAccessLifecycle lifecycle, AuditService auditService, CredentialService credentialService,
			ObjectProvider<ActivationIdentityVerifier> identityVerifiers,
			PlatformTransactionManager transactionManager) {
		this.accountStore = accountStore;
		this.invitationStore = invitationStore;
		this.sender = sender;
		this.scope = scope;
		this.membershipStore = membershipStore;
		this.lifecycle = lifecycle;
		this.auditService = auditService;
		this.credentialService = credentialService;
		this.identityVerifiers = identityVerifiers;
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
		return deliver(account, newInvitation(userID, structureID, false), AuditAction.INVITATION_CREATE);
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

		Delivery delivery = deliver(account, newInvitation(userID, structureID, true), AuditAction.INVITATION_CREATE);

		if (!delivery.sent()) {
			return delivery;
		}

		for (Map.Entry<UUID, Set<UserRole>> entry : roles.entrySet()) {
			membershipStore.save(new StructureMembership(userID, entry.getKey(), entry.getValue()));
			auditService.record(AuditAction.STRUCTURE_ACCESS_GRANT, AuditResult.SUCCESS,
					accountTargets(userID, entry.getKey()), AuditMetadata.rolesGranted(entry.getValue()));
		}

		accountStore.setPendingActivation(userID);
		return delivery;
	}

	@AuditedOperation(value = AuditAction.INVITATION_RESEND, account = 0, structure = 1)
	@Transactional
	public Delivery resendInvitation(UUID userID, UUID structureID) {

		UserAccount account = lockPendingAccount(userID, structureID);
		Invitation current = requireInvitation(userID, structureID);

		if (!isUsable(current, Instant.now())) {
			throw AccountInvitationScope.denied();
		}

		requireReady(account, structureID);
		return deliver(account, newInvitation(userID, structureID, current.reintegration()),
				AuditAction.INVITATION_RESEND);
	}

	@AuditedOperation(value = AuditAction.INVITATION_REPLACE, account = 0, structure = 1)
	@Transactional(propagation = Propagation.NEVER)
	public Delivery replaceInvitation(UUID userID, UUID structureID, String correctedEmail) {

		UUID replacementID = replacementTransaction.execute(transaction -> {
			UserAccount account = lockPendingAccount(userID, structureID);
			Invitation current = requireInvitation(userID, structureID);

			if (current.completedAt() != null || (current.expiresAt() != null && !isUsable(current, Instant.now()))) {
				throw AccountInvitationScope.denied();
			}

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
			return deliver(account, replacement, AuditAction.INVITATION_REPLACE);
		});
	}

	@AuditedOperation(value = AuditAction.INVITATION_REVOKE, account = 0, structure = 1)
	@Transactional
	public void cancelInvitation(UUID userID, UUID structureID) {

		UserAccount account = lockPendingAccount(userID, structureID);
		Invitation invitation = requireInvitation(userID, structureID);

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
							&& !Instant.now().isBefore(invitation.expiresAt()))
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

		Invitation candidate = invitationStore.findByID(invitationID).orElseThrow(AccountInvitationScope::denied);
		UserAccount account = accountStore.findByIdForUpdate(candidate.userID())
				.orElseThrow(AccountInvitationScope::denied);
		Invitation invitation = invitationStore.findByID(invitationID).orElseThrow(AccountInvitationScope::denied);

		if (account.getStatu() != UserAccountStatus.PENDING_ACTIVATION || !isUsable(invitation, Instant.now())
				|| !MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),
						invitation.tokenDigest().getBytes(StandardCharsets.US_ASCII))) {
			throw AccountInvitationScope.denied();
		}

		requireReady(account, invitation.structureID());
		ActivationIdentityVerifier verifier = identityVerifiers.getIfAvailable();

		if (verifier == null || !verifier.verify(invitationID, account.getUserID(), account.getPhoneNumber(), proof)) {
			throw AccountInvitationScope.denied();
		}

		UserCredential credential = credentialService.createCredential(account.getUserID(), rawPassword);

		if (!isUsable(invitation, Instant.now())) {
			throw AccountInvitationScope.denied();
		}

		accountStore.activateAccount(account.getUserID(), userName, credential);
		invitationStore.save(new Invitation(invitationID, account.getUserID(), invitation.structureID(), null, null,
				Instant.now(), invitation.reintegration()));
		record(AuditAction.INVITATION_TOKEN_USE, AuditResult.SUCCESS, invitation);
		record(invitation.reintegration() ? AuditAction.ACCOUNT_REACTIVATE : AuditAction.ACCOUNT_ACTIVATE,
				AuditResult.SUCCESS, invitation);
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

	private Delivery deliver(UserAccount account, Invitation invitation, AuditAction action) {

		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		if (!sender.send(account.getEmail(), invitation.invitationID(), token)) {
			record(action, AuditResult.FAILURE, invitation);
			return new Delivery(false);
		}

		invitationStore.save(new Invitation(invitation.invitationID(), account.getUserID(), invitation.structureID(),
				digest(token), Instant.now().plus(INVITATION_VALIDITY), null, invitation.reintegration()));
		record(action, AuditResult.SUCCESS, invitation);
		return new Delivery(true);
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

	private String digest(String token) {

		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 indisponible.", exception);
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

	public record Delivery(boolean sent) {
	}
}
