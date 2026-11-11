package fr.gestionplus.server.account;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.RoleHierarchyPolicy;
import fr.gestionplus.server.auth.RoleMfaPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.EmployeeResponsibilityStore;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Service
public class StructureAccessService {

	private final AccountStore store;
	private final AuthorizationService authorizationService;
	private final StructureMembershipStore structureMembershipStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final RoleHierarchyPolicy roleHierarchyPolicy;
	private final AuditService auditService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final EmployeeResponsibilityStore responsibilityStore;
	private final AccountAccessLifecycle accountAccessLifecycle;

	public StructureAccessService(AuthorizationService authorizationService, AccountStore store,
			StructureMembershipStore structureMembershipStore, AuthenticatedUserProvider authenticatedUserProvider,
			AuditService auditService, RoleHierarchyPolicy roleHierarchyPolicy,
			OrganizationalStructureStore organizationalStructureStore, EmployeeResponsibilityStore responsibilityStore,
			AccountAccessLifecycle accountAccessLifecycle) {

		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.store = Objects.requireNonNull(store);
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.roleHierarchyPolicy = Objects.requireNonNull(roleHierarchyPolicy);
		this.auditService = Objects.requireNonNull(auditService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.responsibilityStore = Objects.requireNonNull(responsibilityStore);
		this.accountAccessLifecycle = Objects.requireNonNull(accountAccessLifecycle);
	}

	@AuditedOperation(value = AuditAction.STRUCTURE_ACCESS_REVOKE, account = 0, structure = 1)
	@Transactional
	public void revokeStructureAccess(UUID targetUserId, UUID structureID) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		UUID actorUserId = principal.getUserID();
		Objects.requireNonNull(targetUserId);
		Objects.requireNonNull(structureID);

		if (targetUserId.equals(actorUserId)) {
			throw denied();
		}

		OrganizationalStructure structure = lockStructure(structureID);
		UserAccount targetAccount = lockTargetAccount(principal, targetUserId, structure);
		Set<UserRole> actorRoles = authorizationService.rolesInStructureForSession(principal, structureID);
		requireAccountManagementScope(actorUserId, actorRoles, targetAccount, structure);
		StructureMembership membership = requireMembership(targetUserId, structureID);

		if (membership.getRoles().contains(UserRole.ADMIN) && actorRoles.contains(UserRole.SUPER_ADMIN)) {
			throw new AccessDeniedException(
					"La structure doit conserver un ADMIN actif. Choisissez un successeur ou confirmez la reprise de la fonction.");
		}

		if (!roleHierarchyPolicy.canAssignRoles(actorRoles, membership.getRoles())) {
			throw denied();
		}

		structureMembershipStore.deleteByUserAndStructure(targetUserId, structureID);

		auditService.record(AuditAction.STRUCTURE_ACCESS_REVOKE, AuditResult.SUCCESS,
				auditTargets(targetUserId, structureID),
				AuditMetadata.rolesRevoked(membership.getRoles()));
		accountAccessLifecycle.disableIfWithoutAccess(targetAccount, actorUserId, structureID);
	}

	@AuditedOperation(value = AuditAction.STRUCTURE_ACCESS_GRANT, account = 0, structure = 1)
	@Transactional
	public void addStructureAccess(UUID targetUserId, UUID structureID, Set<UserRole> roles) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		UUID actorUserId = principal.getUserID();

		if (targetUserId == null || structureID == null || roles == null || roles.isEmpty()) {
			throw denied();
		}

		OrganizationalStructure structure = lockStructure(structureID);
		UserAccount targetAccount = lockTargetAccount(principal, targetUserId, structure);
		Set<UserRole> actorRoles = authorizationService.rolesInStructureForSession(principal, structureID);
		requireAccountManagementScope(actorUserId, actorRoles, targetAccount, structure);
		Set<UserRole> requestedRoles = new HashSet<>(roles);

		if (!roleHierarchyPolicy.canAssignRoles(actorRoles, requestedRoles)) {
			throw denied();
		}

		if (targetAccount.getStatu() == UserAccountStatus.DISABLED) {
			throw denied();
		}

		if (structureMembershipStore.findByUserAndStructure(targetUserId, structureID).isPresent()) {
			throw new AccessDeniedException("Cet employé est déjà rattaché à cette structure.");
		}

		boolean mfaNewlyRequired = targetAccount.getStatu() == UserAccountStatus.ACTIVE
				&& RoleMfaPolicy.requiresAny(requestedRoles) && !authorizationService.requiresMfa(targetUserId);
		structureMembershipStore.save(new StructureMembership(targetUserId, structureID, requestedRoles));

		auditService.record(AuditAction.STRUCTURE_ACCESS_GRANT, AuditResult.SUCCESS,
				auditTargets(targetUserId, structureID),
				AuditMetadata.rolesGranted(requestedRoles));

		if (mfaNewlyRequired) {
			accountAccessLifecycle.invalidateSessions(targetAccount,
					new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
		}

	}

	@AuditedOperation(value = AuditAction.STRUCTURE_ROLES_CHANGE, account = 0, structure = 1)
	@Transactional
	public void changeStructureRoles(UUID targetUserId, UUID structureID, Set<UserRole> roles) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		UUID actorUserId = principal.getUserID();

		if (targetUserId == null || structureID == null || roles == null || roles.isEmpty()) {
			throw denied();
		}

		OrganizationalStructure structure = lockStructure(structureID);
		UserAccount targetAccount = lockTargetAccount(principal, targetUserId, structure);
		Set<UserRole> actorRoles = authorizationService.rolesInStructureForSession(principal, structureID);
		requireAccountManagementScope(actorUserId, actorRoles, targetAccount, structure);
		StructureMembership membership = requireMembership(targetUserId, structureID);
		Set<UserRole> requestedRoles = new HashSet<>(roles);

		if (targetAccount.getStatu() == UserAccountStatus.DISABLED
				|| !roleHierarchyPolicy.canAssignRoles(actorRoles, membership.getRoles())
				|| !roleHierarchyPolicy.canAssignRoles(actorRoles, requestedRoles)) {
			throw denied();
		}

		if (membership.getRoles().equals(requestedRoles)) {
			return;
		}

		Set<UserRole> grantedRoles = new HashSet<>(requestedRoles);
		grantedRoles.removeAll(membership.getRoles());
		Set<UserRole> revokedRoles = new HashSet<>(membership.getRoles());
		revokedRoles.removeAll(requestedRoles);

		boolean mfaNewlyRequired = targetAccount.getStatu() == UserAccountStatus.ACTIVE
				&& RoleMfaPolicy.requiresAny(requestedRoles) && !authorizationService.requiresMfa(targetUserId);
		structureMembershipStore.save(new StructureMembership(targetUserId, structureID, requestedRoles));
		auditService.record(AuditAction.STRUCTURE_ROLES_CHANGE, AuditResult.SUCCESS,
				auditTargets(targetUserId, structureID),
				new AuditMetadata(grantedRoles, revokedRoles));

		if (mfaNewlyRequired) {
			accountAccessLifecycle.invalidateSessions(targetAccount,
					new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
		}

	}

	private OrganizationalStructure lockStructure(UUID structureID) {
		return organizationalStructureStore.findByIdForUpdate(structureID)
				.orElseThrow(() -> denied(AuditReason.OUTSIDE_STRUCTURE));
	}

	private UserAccount lockTargetAccount(UserAccount principal, UUID targetUserId, OrganizationalStructure structure) {

		UUID actorUserId = principal.getUserID();

		if (actorUserId.equals(targetUserId)) {
			throw denied();
		}

		if (!authorizationService.hasTenantMembership(targetUserId, structure.getTenantID())) {
			throw denied(AuditReason.OUTSIDE_TENANT);
		}

		Map<UUID, UserAccount> locked = AccountLocks.lock(store, this::denied, actorUserId, targetUserId);
		AccountLocks.requireCurrentSession(locked.get(actorUserId), principal);
		UserAccount targetAccount = locked.get(targetUserId);

		if (!targetUserId.equals(targetAccount.getUserID())
				|| authorizationService.isDesignatedSuperAdmin(targetUserId)) {
			throw denied();
		}

		return targetAccount;
	}

	void requireAccountManagementScope(UUID actorUserId, Set<UserRole> actorRoles, UserAccount targetAccount,
			OrganizationalStructure structure) {

		if (actorRoles.isEmpty()) {
			throw denied(AuditReason.OUTSIDE_STRUCTURE);
		}

		boolean manager = actorRoles.contains(UserRole.MANAGER);
		boolean secretary = actorRoles.contains(UserRole.SECRETARY);
		boolean elevated = actorRoles.contains(UserRole.ADMIN) || actorRoles.contains(UserRole.R_H)
				|| actorRoles.contains(UserRole.SUPER_ADMIN);

		if (manager || secretary) {
			requireManageableAccount(actorUserId, actorRoles, targetAccount, structure, elevated);
		}

	}

	private void requireManageableAccount(UUID actorUserId, Set<UserRole> actorRoles, UserAccount targetAccount,
			OrganizationalStructure structure, boolean elevated) {

		UUID employeeID = targetAccount.getEmployeeID();

		if (!elevated && (!actorRoles.contains(UserRole.MANAGER) || employeeID == null
				|| !responsibilityStore.isResponsibleFor(actorUserId, employeeID, structure.getStructureID()))) {
			throw denied();
		}

		for (StructureMembership membership : structureMembershipStore.findByUserAndTenant(targetAccount.getUserID(),
				structure.getTenantID())) {

			if (!membership.getRoles().isEmpty()
					&& !roleHierarchyPolicy.canAssignRoles(actorRoles, membership.getRoles())) {
				throw denied();
			}

		}

	}

	private StructureMembership requireMembership(UUID targetUserId, UUID structureID) {
		return structureMembershipStore.findByUserAndStructure(targetUserId, structureID)
				.filter(membership -> targetUserId.equals(membership.getUserID())
						&& structureID.equals(membership.getStructureID()))
				.orElseThrow(() -> denied(AuditReason.OUTSIDE_STRUCTURE));
	}

	private Set<AuditTarget> auditTargets(UUID userID, UUID structureID) {
		return Set.of(new AuditTarget(AuditTargetType.ACCOUNT, userID),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
	}

	private AccessDeniedException denied() {
		return denied(AuditReason.ACCESS_NOT_AUTHORIZED);
	}

	private AccessDeniedException denied(AuditReason reason) {
		return new AuditedAccessDeniedException("Modification du rattachement impossible ou non autorisée.", reason);
	}
}
