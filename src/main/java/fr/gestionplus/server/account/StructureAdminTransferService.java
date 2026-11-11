package fr.gestionplus.server.account;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Service
public class StructureAdminTransferService {

	private final AccountStore store;
	private final AuthorizationService authorizationService;
	private final StructureMembershipStore structureMembershipStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuditService auditService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantMembershipStore tenantMembershipStore;
	private final AccountAccessLifecycle accountAccessLifecycle;
	private final PrivilegedConfirmationService confirmations;
	private final AccountDeactivationNotifications notifications;

	public StructureAdminTransferService(AuthorizationService authorizationService, AccountStore store,
			StructureMembershipStore structureMembershipStore, AuthenticatedUserProvider authenticatedUserProvider,
			AuditService auditService, OrganizationalStructureStore organizationalStructureStore,
			TenantMembershipStore tenantMembershipStore, AccountAccessLifecycle accountAccessLifecycle,
			PrivilegedConfirmationService confirmations, AccountDeactivationNotifications notifications) {

		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.store = Objects.requireNonNull(store);
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.auditService = Objects.requireNonNull(auditService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantMembershipStore = Objects.requireNonNull(tenantMembershipStore);
		this.accountAccessLifecycle = Objects.requireNonNull(accountAccessLifecycle);
		this.confirmations = Objects.requireNonNull(confirmations);
		this.notifications = Objects.requireNonNull(notifications);
	}

	@AuditedOperation(value = AuditAction.STRUCTURE_ADMIN_TRANSFER_PREVIEW, structure = 0, account = 1)
	@Transactional
	public TransferPreview previewTransfer(UUID structureID, UUID newAdminUserID) {
		return loadTransfer(structureID, newAdminUserID, null).preview();
	}

	@AuditedOperation(value = AuditAction.STRUCTURE_ADMIN_TRANSFER_PREVIEW, structure = 0, account = 1)
	@Transactional
	public TransferPreview previewTransfer(UUID structureID, UUID newAdminUserID, Set<UserRole> rolesToKeep) {
		return loadTransfer(structureID, newAdminUserID, Set.copyOf(rolesToKeep)).preview();
	}

	@AuditedOperation(AuditAction.STRUCTURE_ADMIN_TRANSFER)
	@Transactional
	public TransferResult transferStructureAdmin(TransferPreview confirmed, String proof) {
		Objects.requireNonNull(confirmed);
		TransferState state = loadTransfer(confirmed.structureID(), confirmed.successor().userID(),
				Set.copyOf(confirmed.rolesToKeep()));

		if (!state.preview().equals(confirmed)) {
			throw new AccessDeniedException(
					"Les conditions du transfert ont changé. Une nouvelle confirmation est requise.");
		}

		confirmations.require(state.actor(), Action.STRUCTURE_ADMIN_TRANSFER, state.preview().confirmationDigest(),
				proof);

		UUID structureID = confirmed.structureID();
		moveAdminRole(structureID, state.incumbent().getUserID(), Set.copyOf(confirmed.rolesToKeep()),
				state.successor().getUserID(), confirmed.successor().rolesIn(structureID));

		AuditTarget scope = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		if (confirmed.incumbentWillBeDisabled()) {
			accountAccessLifecycle.disable(state.incumbent(), structureID);
		} else {
			accountAccessLifecycle.invalidateSessions(state.incumbent(), scope);
		}

		accountAccessLifecycle.invalidateSessions(state.successor(), scope);

		return new TransferResult(
				snapshot(store.findById(state.incumbent().getUserID()).orElseThrow(), confirmed.tenantID()),
				snapshot(store.findById(state.successor().getUserID()).orElseThrow(), confirmed.tenantID()));
	}

	private TransferState loadTransfer(UUID structureID, UUID newAdminUserID, Set<UserRole> requestedRolesToKeep) {
		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		UUID actorID = principal.getUserID();

		if (structureID == null || newAdminUserID == null) {
			throw denied();
		}

		OrganizationalStructure structure = organizationalStructureStore.findByIdForUpdate(structureID)
				.orElseThrow(StructureAdminTransferService::denied);
		UUID tenantID = structure.getTenantID();
		requireTenantMembership(newAdminUserID, tenantID);

		StructureMembership oldAdminMembership = structureMembershipStore.findAdminByStructure(structureID)
				.orElseThrow(StructureAdminTransferService::denied);
		UUID oldAdminUserID = oldAdminMembership.getUserID();

		if (newAdminUserID.equals(oldAdminUserID)) {
			throw denied();
		}

		Map<UUID, UserAccount> locked = lockAccounts(actorID, oldAdminUserID, newAdminUserID);
		requireAuthorizedSuperAdmin(principal, structureID);
		UserAccount actor = locked.get(actorID);
		UserAccount incumbent = locked.get(oldAdminUserID);
		UserAccount successor = locked.get(newAdminUserID);

		if (actor.getSessionVersion() != principal.getSessionVersion()
				|| successor.getStatu() != UserAccountStatus.ACTIVE
				|| (!actorID.equals(newAdminUserID) && authorizationService.isDesignatedSuperAdmin(newAdminUserID))) {
			throw denied();
		}

		Set<UserRole> previousRoles = new HashSet<>(oldAdminMembership.getRoles());
		previousRoles.remove(UserRole.ADMIN);
		Set<UserRole> rolesToKeep = requestedRolesToKeep == null ? previousRoles : requestedRolesToKeep;

		if (!previousRoles.containsAll(rolesToKeep) || rolesToKeep.contains(UserRole.SUPER_ADMIN)) {
			throw denied();
		}

		PrivilegedAccountSnapshot incumbentSnapshot = snapshot(incumbent, tenantID);
		PrivilegedAccountSnapshot successorSnapshot = snapshot(successor, tenantID);

		if (successorSnapshot.rolesIn(structureID).contains(UserRole.SUPER_ADMIN)) {
			throw denied();
		}

		boolean createsMembership = successorSnapshot.memberships().stream()
				.noneMatch(membership -> structureID.equals(membership.structureID()));
		boolean disableIncumbent = rolesToKeep.isEmpty() && !authorizationService.isDesignatedSuperAdmin(oldAdminUserID)
				&& incumbent.getStatu() != UserAccountStatus.DISABLED
				&& !accountAccessLifecycle.hasValidAccessOutside(incumbent, structureID);
		TransferPreview preview = new TransferPreview(actorID, actor.getSessionVersion(), tenantID, structureID,
				incumbentSnapshot, successorSnapshot, rolesToKeep.stream().sorted().toList(), createsMembership,
				disableIncumbent);

		return new TransferState(preview, actor, incumbent, successor);
	}

	Map<UUID, UserAccount> lockAccounts(UUID... userIDs) {
		return AccountLocks.lock(store, StructureAdminTransferService::denied, userIDs);
	}

	PrivilegedAccountSnapshot snapshot(UserAccount account, UUID tenantID) {
		return PrivilegedAccountSnapshot.capture(account,
				structureMembershipStore.findByUserAndTenant(account.getUserID(), tenantID));
	}

	void moveAdminRole(UUID structureID, UUID oldAdminID, Set<UserRole> retainedRoles, UUID newAdminID,
			Set<UserRole> currentSuccessorRoles) {

		if (retainedRoles.isEmpty()) {
			structureMembershipStore.deleteByUserAndStructure(oldAdminID, structureID);
		} else {
			structureMembershipStore.saveAndFlush(new StructureMembership(oldAdminID, structureID, retainedRoles));
		}

		Set<UserRole> newRoles = new HashSet<>(currentSuccessorRoles);
		newRoles.add(UserRole.ADMIN);
		structureMembershipStore.saveAndFlush(new StructureMembership(newAdminID, structureID, newRoles));

		auditService.record(AuditAction.STRUCTURE_ADMIN_TRANSFER, AuditResult.SUCCESS,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, oldAdminID),
						new AuditTarget(AuditTargetType.ACCOUNT, newAdminID),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID)));
		notifications.enqueueAdminTransfer(oldAdminID, newAdminID, structureID);
	}

	@AuditedOperation(value = AuditAction.STRUCTURE_ADMIN_TRANSFER_PREVIEW, structure = 0, account = 1)
	@Transactional
	public List<StructureMembership> findOtherAdminMemberships(UUID structureID, UUID newAdminUserID) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		Objects.requireNonNull(structureID);
		Objects.requireNonNull(newAdminUserID);

		OrganizationalStructure structure = organizationalStructureStore.findByIdForUpdate(structureID)
				.orElseThrow(StructureAdminTransferService::denied);
		requireAuthorizedSuperAdmin(principal, structureID);
		requireTenantMembership(newAdminUserID, structure.getTenantID());

		if (store.findById(newAdminUserID).filter(account -> account.getStatu() == UserAccountStatus.ACTIVE)
				.isEmpty()) {
			throw denied();
		}

		return structureMembershipStore.findAdminMembershipsByUserAndTenant(newAdminUserID, structure.getTenantID())
				.stream().filter(membership -> !structureID.equals(membership.getStructureID())).toList();
	}

	private void requireAuthorizedSuperAdmin(UserAccount principal, UUID structureID) {

		if (!authorizationService.rolesInStructureForSession(principal, structureID).contains(UserRole.SUPER_ADMIN)) {
			throw denied();
		}

	}

	private void requireTenantMembership(UUID userID, UUID tenantID) {

		if (tenantMembershipStore.findByUserAndTenant(userID, tenantID)
				.filter(membership -> userID.equals(membership.getUserID())
						&& tenantID.equals(membership.getTenantID()))
				.isEmpty()) {
			throw denied();
		}

	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException("Transfert ADMIN impossible ou non autorisé.");
	}

	public record TransferPreview(UUID actorID, long actorSessionVersion, UUID tenantID, UUID structureID,
			PrivilegedAccountSnapshot incumbent, PrivilegedAccountSnapshot successor, List<UserRole> rolesToKeep,
			boolean createsMembership, boolean incumbentWillBeDisabled) implements AuditTargetSource {

		public TransferPreview { rolesToKeep = rolesToKeep.stream().sorted().toList(); }

		@Override
		public Set<AuditTarget> auditTargets() {
			return Set.copyOf(List.of(new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID),
					new AuditTarget(AuditTargetType.ACCOUNT, incumbent.userID()),
					new AuditTarget(AuditTargetType.ACCOUNT, successor.userID())));
		}

		public String confirmationDigest() {
			return PrivilegedConfirmationService.digest(this);
		}
	}

	public record TransferResult(PrivilegedAccountSnapshot previousAdmin, PrivilegedAccountSnapshot newAdmin) {
	}

	private record TransferState(TransferPreview preview, UserAccount actor, UserAccount incumbent,
			UserAccount successor) {
	}
}
