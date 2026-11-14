package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationDeliveryStore.Claim;
import fr.gestionplus.server.account.AccountInvitationDeliveryStore.Request;
import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Service
public class AccountInvitationDelivery {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountInvitationDelivery.class);
	private static final Duration VALIDITY = Duration.ofDays(7);

	private final AccountInvitationDeliveryStore deliveries;
	private final AccountInvitationStore invitations;
	private final AccountStore accounts;
	private final AccountInvitationScope scope;
	private final StructureMembershipStore memberships;
	private final AccountInvitationSender sender;
	private final InvitationTokenIssuer tokens;
	private final AuditService audit;
	private final TransactionTemplate transaction;
	private final MailDeliveryExecutor executor;

	AccountInvitationDelivery(AccountInvitationDeliveryStore deliveries, AccountInvitationStore invitations,
			AccountStore accounts, AccountInvitationScope scope, StructureMembershipStore memberships,
			AccountInvitationSender sender, InvitationTokenIssuer tokens, AuditService audit,
			PlatformTransactionManager transactionManager, MailDeliveryExecutor executor) {
		this.deliveries = deliveries;
		this.invitations = invitations;
		this.accounts = accounts;
		this.scope = scope;
		this.memberships = memberships;
		this.sender = sender;
		this.tokens = tokens;
		this.audit = audit;
		transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.executor = executor;
	}

	@Scheduled(fixedDelayString = "${gestionplus.activation.delivery-delay:5000}", initialDelayString = "${gestionplus.activation.delivery-initial-delay:5000}")
	public void retryPending() {

		try {

			for (UUID requestID : deliveries.findDue()) {

				if (!executor.trySubmit(() -> deliver(requestID))) {
					break;
				}

			}

		} catch (RuntimeException failure) {
			LOGGER.warn("Lecture des invitations en attente impossible ({})", failure.getClass().getSimpleName());
		}

	}

	public Status deliver(UUID requestID) {

		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return Status.PENDING;
		}

		Claim claim;

		try {
			claim = transaction.execute(status -> deliveries.claim(requestID).orElse(null));
		} catch (RuntimeException failure) {
			LOGGER.warn("Invitation {} conservée pour une prochaine tentative ({})", requestID,
					failure.getClass().getSimpleName());
			return Status.PENDING;
		}

		if (claim == null) {
			return deliveredStatus(requestID);
		}

		try {
			Request request = transaction.execute(status -> authorizedRequest(claim));

			if (request == null) {
				return deliveredStatus(requestID);
			}

			String token = tokens.token(request.requestID(), request.invitationID());

			if (!sender.send(request.email(), request.invitationID(), token)) {
				return postpone(claim, false);
			}

			Status result = transaction.execute(status -> completeLocked(claim));
			return result == null ? deliveredStatus(requestID) : result;
		} catch (AccessDeniedException denied) {
			return postpone(claim, true);
		} catch (RuntimeException failure) {
			LOGGER.warn("Envoi de l’invitation {} à reprendre ({})", requestID, failure.getClass().getSimpleName());
			return postpone(claim, false);
		}

	}

	private Request authorizedRequest(Claim claim) {
		Request candidate = claim.request();
		UserAccount account = scope.lockAndAuthorize(candidate.actorID(), candidate.actorVersion(),
				candidate.userID(), candidate.structureID(), candidate.roles());
		Request request = deliveries.lockClaim(claim).orElse(null);

		if (request == null) {
			return null;
		}

		boolean preparingReintegration = !request.roles().isEmpty();
		UserAccountStatus expected = preparingReintegration ? UserAccountStatus.DISABLED
				: UserAccountStatus.PENDING_ACTIVATION;

		if (account.getStatu() != expected || account.getSessionVersion() != request.targetVersion()
				|| !account.getEmail().equals(request.email())) {
			throw AccountInvitationScope.denied();
		}

		Invitation current = invitations.findByAccount(request.userID()).orElse(null);

		if (request.previousInvitationID() == null ? current != null
				: current == null || !request.previousInvitationID().equals(current.invitationID())
						|| current.completedAt() != null
						|| current.expiresAt() != null && !Instant.now().isBefore(current.expiresAt())) {
			throw AccountInvitationScope.denied();
		}

		scope.requireEmployee(account, request.structureID());

		if (!preparingReintegration) {
			scope.requirePreparedAccess(account, request.structureID());
		}

		String token = tokens.token(request.requestID(), request.invitationID());

		if (!MessageDigest.isEqual(InvitationTokenIssuer.digest(token).getBytes(StandardCharsets.US_ASCII),
				request.tokenDigest().getBytes(StandardCharsets.US_ASCII))) {
			throw new IllegalStateException("La clé d’envoi ne correspond plus à l’invitation conservée.");
		}

		return request;
	}

	private Status completeLocked(Claim claim) {
		Request request = authorizedRequest(claim);

		if (request == null) {
			return null;
		}

		if (!request.roles().isEmpty()) {
			request.roles().forEach((structureID, roles) -> {
				memberships.save(new StructureMembership(request.userID(), structureID, roles));
				audit.recordForActor(AuditAction.STRUCTURE_ACCESS_GRANT, AuditResult.SUCCESS, request.actorID(), null,
						Set.of(new AuditTarget(AuditTargetType.ACCOUNT, request.userID()),
								new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID)),
						AuditMetadata.rolesGranted(roles));
			});
			accounts.setPendingActivation(request.userID());
		}

		invitations.save(new Invitation(request.invitationID(), request.userID(), request.structureID(),
				request.tokenDigest(), Instant.now().plus(VALIDITY), null, request.reintegration()));
		record(request, AuditResult.SUCCESS);

		if (!deliveries.complete(claim)) {
			throw new IllegalStateException("La réservation d’envoi n’est plus valide.");
		}

		return Status.SENT;
	}

	private Status postpone(Claim claim, boolean cancelled) {
		Request request = claim.request();
		boolean updated = false;

		try {
			updated = Boolean.TRUE.equals(transaction.execute(
					status -> cancelled ? deliveries.cancel(claim) : deliveries.retry(claim)));
		} catch (RuntimeException failure) {
			LOGGER.error("État de l’invitation {} conservé sans mise à jour ({})", request.requestID(),
					failure.getClass().getSimpleName());
		}

		try {
			record(request, cancelled ? AuditResult.DENIED : AuditResult.FAILURE);
		} catch (RuntimeException failure) {
			LOGGER.error("Audit de l’envoi {} indisponible ({})", request.requestID(),
					failure.getClass().getSimpleName());
		}

		return updated ? (cancelled ? Status.CANCELLED : Status.PENDING) : deliveredStatus(request.requestID());
	}

	private Status deliveredStatus(UUID requestID) {

		try {
			return transaction.execute(status -> {

				if (deliveries.wasSent(requestID)) {
					return Status.SENT;
				}

				return deliveries.findById(requestID).isPresent() ? Status.PENDING : Status.CANCELLED;
			});
		} catch (RuntimeException failure) {
			return Status.PENDING;
		}

	}

	private void record(Request request, AuditResult result) {
		audit.recordForActor(request.action(), result, request.actorID(), null,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, request.userID()),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, request.structureID()),
						new AuditTarget(AuditTargetType.INVITATION, request.invitationID())),
				result == AuditResult.SUCCESS ? AuditMetadata.empty()
						: AuditMetadata.because(result == AuditResult.DENIED ? AuditReason.ACCESS_NOT_AUTHORIZED
								: AuditReason.OPERATION_FAILED));
	}

	public enum Status {
		PENDING, SENT, CANCELLED
	}
}
