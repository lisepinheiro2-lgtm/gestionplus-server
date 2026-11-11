package fr.gestionplus.server.account;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.RoleHierarchyPolicy;
import fr.gestionplus.server.auth.RolePermissionPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.employee.EmployeeStore;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Component
class AccountInvitationScope {

	private final AccountStore accountStore;
	private final OrganizationalStructureStore structureStore;
	private final StructureMembershipStore membershipStore;
	private final EmployeeStore employeeStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuthorizationService authorizationService;
	private final RoleHierarchyPolicy roleHierarchyPolicy;
	private final StructureAccessService structureAccessService;

	AccountInvitationScope(AccountStore accountStore, OrganizationalStructureStore structureStore,
			StructureMembershipStore membershipStore, EmployeeStore employeeStore,
			AuthenticatedUserProvider authenticatedUserProvider, AuthorizationService authorizationService,
			RoleHierarchyPolicy roleHierarchyPolicy, StructureAccessService structureAccessService) {
		this.accountStore = accountStore;
		this.structureStore = structureStore;
		this.membershipStore = membershipStore;
		this.employeeStore = employeeStore;
		this.authenticatedUserProvider = authenticatedUserProvider;
		this.authorizationService = authorizationService;
		this.roleHierarchyPolicy = roleHierarchyPolicy;
		this.structureAccessService = structureAccessService;
	}

	UserAccount lockAndAuthorize(UUID userID, UUID structureID, Map<UUID, Set<UserRole>> preparedRoles) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		return lockAndAuthorize(principal.getUserID(), principal.getSessionVersion(), userID, structureID,
				preparedRoles);
	}

	UserAccount lockAndAuthorize(UUID actorID, long actorVersion, UUID userID, UUID structureID,
			Map<UUID, Set<UserRole>> preparedRoles) {

		if (userID == null || structureID == null || userID.equals(actorID)) {
			throw denied();
		}

		Set<UUID> structureIDs = new TreeSet<>(membershipStore.findStructureIDsByUser(userID));
		structureIDs.add(structureID);
		structureIDs.addAll(preparedRoles.keySet());

		Map<UUID, OrganizationalStructure> structures = new HashMap<>();

		for (UUID id : structureIDs) {
			structures.put(id, structureStore.findByIdForUpdate(id).orElseThrow(AccountInvitationScope::denied));
		}

		Map<UUID, UserAccount> locked = AccountLocks.lock(accountStore, AccountInvitationScope::denied, actorID,
				userID);
		UserAccount principal = locked.get(actorID);

		if (principal.getStatu() != UserAccountStatus.ACTIVE || principal.getSessionVersion() != actorVersion) {
			throw denied();
		}

		UserAccount account = locked.get(userID);
		UUID tenantID = structures.get(structureID).getTenantID();

		if (!userID.equals(account.getUserID()) || authorizationService.isDesignatedSuperAdmin(userID)
				|| !authorizationService.hasTenantMembership(userID, tenantID)) {
			throw denied();
		}

		List<StructureMembership> currentMemberships = membershipStore.findByUser(userID);
		Map<UUID, Set<UserRole>> currentRoles = new HashMap<>();

		for (StructureMembership membership : currentMemberships) {

			if (!structures.containsKey(membership.getStructureID())) {
				throw denied();
			}

			currentRoles.put(membership.getStructureID(), membership.getRoles());
		}

		for (UUID id : structureIDs) {
			OrganizationalStructure structure = structures.get(id);
			Set<UserRole> actorRoles = authorizationService.rolesInStructureForSession(principal, id);

			if (!tenantID.equals(structure.getTenantID()) || actorRoles.stream()
					.noneMatch(role -> RolePermissionPolicy.allows(role, Permission.INVITE_USERS))) {
				throw denied();
			}

			structureAccessService.requireAccountManagementScope(actorID, actorRoles, account, structure);
			Set<UserRole> existingRoles = currentRoles.getOrDefault(id, Set.of());

			if ((!existingRoles.isEmpty() && !roleHierarchyPolicy.canAssignRoles(actorRoles, existingRoles))
					|| (preparedRoles.containsKey(id)
							&& !roleHierarchyPolicy.canAssignRoles(actorRoles, preparedRoles.get(id)))) {
				throw denied();
			}

		}

		return account;
	}

	void requireEmployee(UserAccount account, UUID structureID) {

		if (account.getEmployeeID() == null) {
			throw denied();
		}

		UUID tenantID = structureStore.findById(structureID).orElseThrow(AccountInvitationScope::denied).getTenantID();
		boolean sameTenant = employeeStore.findById(account.getEmployeeID()).map(Employee::getStructureID)
				.flatMap(structureStore::findById).filter(structure -> tenantID.equals(structure.getTenantID()))
				.isPresent();

		if (!sameTenant) {
			throw denied();
		}

	}

	void requirePreparedAccess(UserAccount account, UUID structureID) {
		UUID tenantID = structureStore.findById(structureID).orElseThrow(AccountInvitationScope::denied).getTenantID();
		List<StructureMembership> memberships = membershipStore.findByUser(account.getUserID());

		if (memberships.isEmpty() || !authorizationService.hasTenantMembership(account.getUserID(), tenantID)) {
			throw denied();
		}

		for (StructureMembership membership : memberships) {

			if (membership.getRoles().isEmpty() || membership.getRoles().contains(UserRole.SUPER_ADMIN)
					|| membership.getRoles().contains(UserRole.ADMIN)
					|| structureStore.findById(membership.getStructureID())
							.filter(structure -> tenantID.equals(structure.getTenantID())).isEmpty()) {
				throw denied();
			}

		}

	}

	static AccessDeniedException denied() {
		return new AccessDeniedException("Opération d’invitation impossible ou non autorisée.");
	}
}
