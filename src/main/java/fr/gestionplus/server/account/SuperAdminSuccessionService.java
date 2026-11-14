package fr.gestionplus.server.account;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetSource;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Service
public class SuperAdminSuccessionService {

	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuthorizationService authorizationService;
	private final TenantOrganizationStore tenantStore;
	private final OrganizationalStructureStore structureStore;
	private final StructureMembershipStore membershipStore;
	private final StructureAdminTransferService adminTransfer;
	private final AccountAccessLifecycle lifecycle;
	private final PrivilegedConfirmationService confirmations;
	private final AuditService auditService;

	public SuperAdminSuccessionService(AuthenticatedUserProvider authenticatedUserProvider,
			AuthorizationService authorizationService, TenantOrganizationStore tenantStore,
			OrganizationalStructureStore structureStore, StructureMembershipStore membershipStore,
			StructureAdminTransferService adminTransfer, AccountAccessLifecycle lifecycle,
			PrivilegedConfirmationService confirmations, AuditService auditService) {
		this.authenticatedUserProvider = authenticatedUserProvider;
		this.authorizationService = authorizationService;
		this.tenantStore = tenantStore;
		this.structureStore = structureStore;
		this.membershipStore = membershipStore;
		this.adminTransfer = adminTransfer;
		this.lifecycle = lifecycle;
		this.confirmations = confirmations;
		this.auditService = auditService;
	}

	@AuditedOperation(value = AuditAction.SUPER_ADMIN_SUCCESSION_PREVIEW, tenant = 0, account = 1)
	@Transactional
	public SuccessionPreview previewSuccession(UUID tenantID, UUID successorID, Set<UUID> adminRolesToTransfer) {
		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		SuccessionState state = load(tenantID, principal.getUserID(), successorID, Set.copyOf(adminRolesToTransfer));

		if (state.outgoing().getSessionVersion() != principal.getSessionVersion()) {
			throw denied();
		}

		return state.preview();
	}

	@AuditedOperation(AuditAction.SUPER_ADMIN_SUCCESSION)
	@Transactional
	public void acceptSuccession(SuccessionPreview confirmed, String outgoingProof, String successorProof) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();

		if (confirmed == null || !principal.getUserID().equals(confirmed.successor().userID())) {
			throw denied();
		}

		SuccessionState state = load(confirmed.tenantID(), confirmed.outgoing().userID(),
				confirmed.successor().userID(), Set.copyOf(confirmed.adminRolesToTransfer()));

		if (state.successor().getSessionVersion() != principal.getSessionVersion()
				|| !confirmed.equals(state.preview())) {
			throw denied();
		}

		String digest = state.preview().confirmationDigest();
		confirmations.require(state.outgoing(), Action.SUPER_ADMIN_SUCCESSION_OFFER, digest, outgoingProof);
		confirmations.require(state.successor(), Action.SUPER_ADMIN_SUCCESSION_ACCEPTANCE, digest, successorProof);

		UUID oldAdminID = state.outgoing().getUserID();
		UUID newAdminID = state.successor().getUserID();

		if (!tenantStore.replaceSuperAdmin(confirmed.tenantID(), oldAdminID, newAdminID)) {
			throw denied();
		}

		for (UUID structureID : confirmed.adminRolesToTransfer()) {
			Set<UserRole> retainedRoles = new HashSet<>(confirmed.outgoing().rolesIn(structureID));
			retainedRoles.remove(UserRole.ADMIN);
			adminTransfer.moveAdminRole(structureID, oldAdminID, retainedRoles, newAdminID,
					confirmed.successor().rolesIn(structureID));
		}

		AuditTarget scope = new AuditTarget(AuditTargetType.TENANT_ORGANIZATION, confirmed.tenantID());

		if (confirmed.outgoingWillBeDisabled()) {
			UUID scopeStructureID = confirmed.adminRolesToTransfer().isEmpty() ? null
					: confirmed.adminRolesToTransfer().getFirst();
			lifecycle.disable(state.outgoing(), scopeStructureID);
		} else {
			lifecycle.invalidateSessions(state.outgoing(), scope);
		}

		lifecycle.invalidateSessions(state.successor(), scope);

		Set<AuditTarget> targets = new HashSet<>();
		targets.add(scope);
		targets.add(new AuditTarget(AuditTargetType.ACCOUNT, oldAdminID));
		targets.add(new AuditTarget(AuditTargetType.ACCOUNT, newAdminID));

		for (UUID structureID : confirmed.adminRolesToTransfer()) {
			targets.add(new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
		}

		auditService.record(AuditAction.SUPER_ADMIN_SUCCESSION, AuditResult.SUCCESS, targets);
	}

	private SuccessionState load(UUID tenantID, UUID outgoingID, UUID successorID, Set<UUID> adminRolesToTransfer) {

		if (tenantID == null || outgoingID == null || successorID == null || outgoingID.equals(successorID)) {
			throw denied();
		}

		List<UUID> structureIDs = structureStore.findIDsByTenant(tenantID).stream().sorted().toList();

		for (UUID structureID : structureIDs) {
			structureStore.findByIdForUpdate(structureID).filter(structure -> tenantID.equals(structure.getTenantID()))
					.orElseThrow(SuperAdminSuccessionService::denied);
		}

		Map<UUID, UserAccount> accounts = adminTransfer.lockAccounts(outgoingID, successorID);
		UserAccount outgoing = accounts.get(outgoingID);
		UserAccount successor = accounts.get(successorID);
		TenantOrganization tenant = tenantStore.findById(tenantID).orElseThrow(SuperAdminSuccessionService::denied);

		if (!tenant.getSuperAdminUserID().equals(outgoingID) || outgoing.getStatu() != UserAccountStatus.ACTIVE
				|| successor.getStatu() != UserAccountStatus.ACTIVE
				|| !authorizationService.hasTenantMembership(outgoingID, tenantID)
				|| !authorizationService.hasTenantMembership(successorID, tenantID)
				|| authorizationService.isDesignatedSuperAdmin(successorID)) {
			throw denied();
		}

		if (!structureIDs.equals(structureStore.findIDsByTenant(tenantID).stream().sorted().toList())
				|| !structureIDs.containsAll(membershipStore.findStructureIDsByUser(outgoingID))
				|| !structureIDs.containsAll(membershipStore.findStructureIDsByUser(successorID))) {
			throw denied();
		}

		PrivilegedAccountSnapshot outgoingSnapshot = adminTransfer.snapshot(outgoing, tenantID);
		PrivilegedAccountSnapshot successorSnapshot = adminTransfer.snapshot(successor, tenantID);

		if (hasInvalidLocalRole(outgoingSnapshot) || hasInvalidLocalRole(successorSnapshot)) {
			throw denied();
		}

		for (UUID structureID : adminRolesToTransfer) {

			if (!structureIDs.contains(structureID)
					|| !outgoingSnapshot.rolesIn(structureID).contains(UserRole.ADMIN)) {
				throw denied();
			}

		}

		boolean disabled = outgoingSnapshot.memberships().stream()
				.noneMatch(membership -> membership.roles().stream().anyMatch(role -> role != UserRole.SUPER_ADMIN
						&& (role != UserRole.ADMIN || !adminRolesToTransfer.contains(membership.structureID()))));

		SuccessionPreview preview = new SuccessionPreview(tenantID, structureIDs, outgoingSnapshot, successorSnapshot,
				adminRolesToTransfer.stream().sorted().toList(), disabled);
		return new SuccessionState(preview, outgoing, successor);
	}

	private boolean hasInvalidLocalRole(PrivilegedAccountSnapshot snapshot) {
		return snapshot.memberships().stream()
				.anyMatch(membership -> membership.roles().contains(UserRole.SUPER_ADMIN));
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException(
				"Succession impossible ou état modifié. Une nouvelle confirmation est requise.");
	}

	public record SuccessionPreview(UUID tenantID, List<UUID> structures, PrivilegedAccountSnapshot outgoing,
			PrivilegedAccountSnapshot successor, List<UUID> adminRolesToTransfer, boolean outgoingWillBeDisabled)
			implements AuditTargetSource {

		public SuccessionPreview {
			structures = structures.stream().sorted().toList();
			adminRolesToTransfer = adminRolesToTransfer.stream().sorted().toList();
		}

		@Override
		public Set<AuditTarget> auditTargets() {
			return Set.copyOf(List.of(new AuditTarget(AuditTargetType.TENANT_ORGANIZATION, tenantID),
					new AuditTarget(AuditTargetType.ACCOUNT, outgoing.userID()),
					new AuditTarget(AuditTargetType.ACCOUNT, successor.userID())));
		}

		public String confirmationDigest() {
			return PrivilegedConfirmationService.digest(this);
		}
	}

	private record SuccessionState(SuccessionPreview preview, UserAccount outgoing, UserAccount successor) {
	}
}
