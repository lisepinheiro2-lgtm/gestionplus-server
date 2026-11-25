package fr.gestionplus.server.account;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionPreview.Assignments;
import fr.gestionplus.server.account.SuccessionPreview.LocalRoles;
import fr.gestionplus.server.account.SuccessionPreview.Participant;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Component
public class SuccessionScope {

	private final OrganizationalStructureStore structures;
	private final StructureMembershipStore memberships;
	private final TenantOrganizationStore tenants;
	private final AuthorizationService authorization;
	private final StructureAdminTransferService adminTransfer;
	private final SuccessionOfferStore offers;

	public SuccessionScope(OrganizationalStructureStore structures, StructureMembershipStore memberships,
			TenantOrganizationStore tenants, AuthorizationService authorization, StructureAdminTransferService adminTransfer,
			SuccessionOfferStore offers) {
		this.structures = structures;
		this.memberships = memberships;
		this.tenants = tenants;
		this.authorization = authorization;
		this.adminTransfer = adminTransfer;
		this.offers = offers;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Locked lock(UUID tenantID, UUID outgoingID, UUID successorID) {
		if (tenantID == null || outgoingID == null || successorID == null || outgoingID.equals(successorID)) {
			throw new AccessDeniedException("Participants de succession invalides.");
		}

		List<UUID> structureIDs = structures.findIDsByTenant(tenantID).stream().sorted().toList();
		for (UUID structureID : structureIDs) {
			structures.findByIdForUpdate(structureID).filter(structure -> tenantID.equals(structure.getTenantID()))
					.orElseThrow(SuccessionScope::scopeChanged);
		}
		Map<UUID, UserAccount> accounts = adminTransfer.lockAccounts(outgoingID, successorID);
		UserAccount outgoing = accounts.get(outgoingID);
		UserAccount successor = accounts.get(successorID);
		TenantOrganization tenant = tenants.findByIdForUpdate(tenantID)
				.orElseThrow(() -> new AccessDeniedException("Organisation de succession introuvable."));

		if (!structureIDs.equals(structures.findIDsByTenant(tenantID).stream().sorted().toList())) {
			throw scopeChanged();
		}

		PrivilegedAccountSnapshot outgoingSnapshot = adminTransfer.snapshot(outgoing, tenantID);
		PrivilegedAccountSnapshot successorSnapshot = adminTransfer.snapshot(successor, tenantID);
		Eligibility eligibility = eligibility(tenant, structureIDs, outgoing, successor, outgoingSnapshot, successorSnapshot);
		return new Locked(tenantID, structureIDs, outgoing, successor, outgoingSnapshot, successorSnapshot, eligibility);
	}

	/**
	 * Checks participant membership without taking a proposal lock first. The caller must require an active HTTP session.
	 * The returned snapshots belong to the current transaction. An eligibility loss can be used to commit a closure.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LockedOffer lockOfferForParticipant(UUID offerID, UserAccount principal) {
		SuccessionOffer summary = offers.findById(offerID).filter(offer -> offer.hasParticipant(principal.getUserID()))
				.orElseThrow(SuccessionScope::offerDenied);
		Locked current = lock(summary.tenantID(), summary.outgoingUserID(), summary.successorUserID());
		UserAccount participant = current.requireParticipant(principal);
		SuccessionOffer offer = offers.findByIdForUpdate(offerID).orElseThrow(SuccessionScope::offerDenied);
		return new LockedOffer(current, offer, participant);
	}

	public Set<UUID> unavailableAdminChoices(Locked current, Set<UUID> selected) {
		return Set.copyOf(selected.stream().filter(id -> !current.structureIDs().contains(id)
				|| !current.outgoingSnapshot().rolesIn(id).contains(UserRole.ADMIN)).toList());
	}

	public SuccessionPreview preview(Locked current, Set<UUID> selected) {
		Set<UUID> choices = Set.copyOf(selected);
		if (current.eligibility() != Eligibility.ELIGIBLE || !unavailableAdminChoices(current, choices).isEmpty()) {
			throw new AccessDeniedException("Le périmètre du transfert doit être revu.");
		}

		Assignments outgoingBefore = priorAssignments(current.outgoingSnapshot(), true);
		Assignments successorBefore = priorAssignments(current.successorSnapshot(), false);
		List<LocalRoles> outgoingAfter = new ArrayList<>(outgoingBefore.memberships());
		List<LocalRoles> successorAfter = new ArrayList<>(successorBefore.memberships());
		for (UUID structureID : choices) {
			outgoingAfter.removeIf(membership -> membership.structureID().equals(structureID));
			Set<UserRole> retainedRoles = new HashSet<>(outgoingBefore.rolesIn(structureID));
			retainedRoles.remove(UserRole.ADMIN);
			if (!retainedRoles.isEmpty()) {
				outgoingAfter.add(new LocalRoles(structureID, List.copyOf(retainedRoles)));
			}

			successorAfter.removeIf(membership -> membership.structureID().equals(structureID));
			Set<UserRole> successorRoles = new HashSet<>(successorBefore.rolesIn(structureID));
			successorRoles.add(UserRole.ADMIN);
			successorAfter.add(new LocalRoles(structureID, List.copyOf(successorRoles)));
		}
		boolean disabled = outgoingAfter.stream().allMatch(membership -> membership.roles().isEmpty());
		if (disabled) {
			outgoingAfter.clear();
		}
		Set<UUID> affectedStructures = new HashSet<>(choices);
		outgoingBefore.memberships().forEach(membership -> affectedStructures.add(membership.structureID()));
		successorBefore.memberships().forEach(membership -> affectedStructures.add(membership.structureID()));
		return new SuccessionPreview(current.tenantID(), List.copyOf(affectedStructures),
				new Participant(current.outgoing().getUserID(), outgoingBefore, new Assignments(true, false, outgoingAfter)),
				new Participant(current.successor().getUserID(), successorBefore, new Assignments(true, true, successorAfter)),
				List.copyOf(choices), disabled);
	}

	private Eligibility eligibility(TenantOrganization tenant, List<UUID> structureIDs, UserAccount outgoing,
			UserAccount successor, PrivilegedAccountSnapshot outgoingSnapshot, PrivilegedAccountSnapshot successorSnapshot) {
		UUID outgoingID = outgoing.getUserID();
		UUID successorID = successor.getUserID();
		if (!tenant.getSuperAdminUserID().equals(outgoingID) || authorization.isDesignatedSuperAdmin(successorID)) {
			return Eligibility.SUPER_ADMIN_ASSIGNMENT_CHANGED;
		}
		if (outgoing.getStatu() != UserAccountStatus.ACTIVE || successor.getStatu() != UserAccountStatus.ACTIVE) {
			return Eligibility.INACTIVE_PARTICIPANT;
		}
		if (!authorization.hasTenantMembership(outgoingID, tenant.getTenantID())
				|| !authorization.hasTenantMembership(successorID, tenant.getTenantID())) {
			return Eligibility.MISSING_TENANT_MEMBERSHIP;
		}
		if (!structureIDs.containsAll(memberships.findStructureIDsByUser(outgoingID))
				|| !structureIDs.containsAll(memberships.findStructureIDsByUser(successorID))
				|| hasInvalidLocalRole(outgoingSnapshot) || hasInvalidLocalRole(successorSnapshot)) {
			return Eligibility.OUTSIDE_SUPPORTED_SCOPE;
		}
		return Eligibility.ELIGIBLE;
	}

	private static Assignments priorAssignments(PrivilegedAccountSnapshot snapshot, boolean superAdmin) {
		return new Assignments(true, superAdmin, snapshot.memberships().stream()
				.map(membership -> new LocalRoles(membership.structureID(), membership.roles())).toList());
	}

	private static boolean hasInvalidLocalRole(PrivilegedAccountSnapshot snapshot) {
		return snapshot.memberships().stream().anyMatch(membership -> membership.roles().contains(UserRole.SUPER_ADMIN));
	}

	private static ConcurrencyFailureException scopeChanged() {
		return new ConcurrencyFailureException("Les structures de l’organisation ont changé pendant leur verrouillage.");
	}

	private static AccessDeniedException offerDenied() {
		return new AccessDeniedException("Proposition de succession non accessible.");
	}

	public record LockedOffer(Locked scope, SuccessionOffer offer, UserAccount participant) {
	}

	public enum Eligibility {
		ELIGIBLE, SUPER_ADMIN_ASSIGNMENT_CHANGED, INACTIVE_PARTICIPANT, MISSING_TENANT_MEMBERSHIP,
		OUTSIDE_SUPPORTED_SCOPE
	}

	public record Locked(UUID tenantID, List<UUID> structureIDs, UserAccount outgoing, UserAccount successor,
			PrivilegedAccountSnapshot outgoingSnapshot, PrivilegedAccountSnapshot successorSnapshot, Eligibility eligibility) {

		public UserAccount requireParticipant(UserAccount principal) {
			UserAccount participant = outgoing.getUserID().equals(principal.getUserID()) ? outgoing : successor;
			AccountLocks.requireCurrentSession(participant, principal);
			return participant;
		}
	}
}
