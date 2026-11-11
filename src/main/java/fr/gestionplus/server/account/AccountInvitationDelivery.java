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
import org.springframework.transaction.support.TransactionTemplate;

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

	AccountInvitationDelivery(AccountInvitationDeliveryStore deliveries, AccountInvitationStore invitations,
			AccountStore accounts, AccountInvitationScope scope, StructureMembershipStore memberships,
			AccountInvitationSender sender, InvitationTokenIssuer tokens, AuditService audit,
			PlatformTransactionManager transactionManager) {
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
	}

	@Scheduled(fixedDelayString = "${gestionplus.activation.delivery-delay:5000}", initialDelayString = "${gestionplus.activation.delivery-initial-delay:5000}")
	public void retryPending() {

		try {

			for (UUID requestID : deliveries.findDue()) {
				deliver(requestID);
			}

		} catch (RuntimeException failure) {
			LOGGER.warn("Lecture des invitations en attente impossible ({})", failure.getClass().getSimpleName());
		}

	}

	public Status deliver(UUID requestID) {
		Request candidate;

		try {
			candidate = transaction.execute(status -> deliveries.findById(requestID).orElse(null));
		} catch (RuntimeException failure) {
			LOGGER.warn("Invitation {} conservée pour une prochaine tentative ({})", requestID,
					failure.getClass().getSimpleName());
			return Status.PENDING;
		}

		if (candidate == null) {
			return deliveredStatus(requestID);
		}

		try {
			return transaction.execute(status -> deliverLocked(candidate));
		} catch (AccessDeniedException denied) {
			return postpone(candidate, true);
		} catch (RuntimeException failure) {
			LOGGER.warn("Envoi de l’invitation {} à reprendre ({})", requestID, failure.getClass().getSimpleName());
			return postpone(candidate, false);
		}

	}

	private Status deliverLocked(Request candidate) {
		UserAccount account = scope.lockAndAuthorize(candidate.actorID(), candidate.actorVersion(),
				candidate.userID(), candidate.structureID(), candidate.roles());
		Request request = deliveries.lockById(candidate.requestID()).orElse(null);

		if (request == null) {
			return deliveredStatus(candidate.requestID());
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

		if (!sender.send(request.email(), request.invitationID(), token)) {
			deliveries.retry(request.requestID());
			record(request, AuditResult.FAILURE);
			return Status.PENDING;
		}

		if (preparingReintegration) {
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
		deliveries.complete(request.requestID());
		return Status.SENT;
	}

	private Status postpone(Request request, boolean cancelled) {

		try {
			transaction.executeWithoutResult(status -> {

				if (cancelled) {
					deliveries.cancel(request.requestID());
				} else {
					deliveries.retry(request.requestID());
				}

			});
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

		return cancelled ? Status.CANCELLED : Status.PENDING;
	}

	private Status deliveredStatus(UUID requestID) {

		try {
			return transaction.execute(status -> deliveries.wasSent(requestID)) ? Status.SENT : Status.CANCELLED;
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
