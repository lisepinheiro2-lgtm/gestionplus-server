package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Component
public class SuccessionTransfer {

	private final SuccessionScope scope;
	private final SuccessionSecurity security;
	private final SuccessionOfferStore offers;
	private final TenantOrganizationStore tenants;
	private final StructureAdminTransferService adminTransfer;
	private final AccountAccessLifecycle lifecycle;
	private final AuditService audit;

	public SuccessionTransfer(SuccessionScope scope, SuccessionSecurity security, SuccessionOfferStore offers,
			TenantOrganizationStore tenants, StructureAdminTransferService adminTransfer, AccountAccessLifecycle lifecycle,
			AuditService audit) {
		this.scope = scope;
		this.security = security;
		this.offers = offers;
		this.tenants = tenants;
		this.adminTransfer = adminTransfer;
		this.lifecycle = lifecycle;
		this.audit = audit;
	}

	/** Call after acquiring the scope and validating the requested revision in the same transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Instant transferConfirmed(SuccessionScope.Locked locked, UUID offerID, SuccessionRevision revision) {
		SuccessionOffer current = offers.findByIdForUpdate(offerID).orElseThrow(SuccessionTransfer::denied);
		SuccessionPreview preview = scope.preview(locked, Set.copyOf(current.adminRolesToTransfer()));
		if (!preview.equals(revision.preview())) {
			throw denied();
		}
		Instant authorizedAt = security.authorizeFinalization(current, revision, locked);
		UUID oldAdminID = locked.outgoing().getUserID();
		UUID newAdminID = locked.successor().getUserID();
		if (!tenants.replaceSuperAdmin(preview.tenantID(), oldAdminID, newAdminID)) {
			throw denied();
		}

		for (UUID structureID : preview.adminRolesToTransfer()) {
			adminTransfer.moveAdminRole(newAdminID, structureID, oldAdminID,
					preview.outgoing().after().rolesIn(structureID), newAdminID, preview.successor().before().rolesIn(structureID));
		}

		AuditTarget org = new AuditTarget(AuditTargetType.TENANT_ORGANIZATION, preview.tenantID());
		if (preview.outgoingWillBeDisabled()) {
			lifecycle.disable(locked.outgoing(),
					preview.adminRolesToTransfer().isEmpty() ? null : preview.adminRolesToTransfer().getFirst());
		} else {
			lifecycle.invalidateSessions(locked.outgoing(), org);
		}
		lifecycle.invalidateSessions(locked.successor(), org);

		if (!offers.complete(offerID, revision.revision(), authorizedAt)) {
			throw denied();
		}

		Set<AuditTarget> targets = new HashSet<>(preview.auditTargets());
		for (UUID structureID : preview.adminRolesToTransfer()) {
			targets.add(new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
		}
		audit.record(AuditAction.SUPER_ADMIN_SUCCESSION, AuditResult.SUCCESS, targets);
		return authorizedAt;
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException("Le transfert ne correspond plus à la révision validée.");
	}
}
