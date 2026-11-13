package fr.gestionplus.server.account;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.RolePermissionPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.EmployeeResponsibilityStore;
import fr.gestionplus.server.employee.EmployeeStore;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Service
public class AccountProvisioningService {

	private final AccountStore store;
	private final EmployeeStore employeeStore;
	private final EmployeeResponsibilityStore responsibilityStore;
	private final AuditService auditService;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuthorizationService authorizationService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantMembershipStore tenantMembershipStore;

	public AccountProvisioningService(AccountStore store, EmployeeStore employeeStore,
			EmployeeResponsibilityStore responsibilityStore, AuditService auditService,
			AuthenticatedUserProvider authenticatedUserProvider, AuthorizationService authorizationService,
			OrganizationalStructureStore organizationalStructureStore, TenantMembershipStore tenantMembershipStore) {
		this.store = Objects.requireNonNull(store);
		this.employeeStore = Objects.requireNonNull(employeeStore);
		this.responsibilityStore = Objects.requireNonNull(responsibilityStore);
		this.auditService = Objects.requireNonNull(auditService);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantMembershipStore = Objects.requireNonNull(tenantMembershipStore);
	}

	@AuditedOperation(value = AuditAction.ACCOUNT_CREATE, structure = 0)
	@Transactional
	public UUID createPendingAccount(UUID structureID, String lastName, String firstName, String email,
			String phoneNumber) {

		return createPendingAccount(structureID, null, lastName, firstName, email, phoneNumber);
	}

	@AuditedOperation(value = AuditAction.ACCOUNT_CREATE, structure = 0)
	@Transactional
	public UUID createPendingAccount(UUID structureID, UUID employeeID, String lastName, String firstName, String email,
			String phoneNumber) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		UUID actorUserId = principal.getUserID();

		if (structureID == null) {
			throw denied();
		}

		OrganizationalStructure structure = organizationalStructureStore.findByIdForUpdate(structureID)
				.orElseThrow(this::denied);
		UserAccount currentActor = AccountLocks.lock(store, this::denied, actorUserId).get(actorUserId);
		AccountLocks.requireCurrentSession(currentActor, principal);
		Set<UserRole> actorRoles = authorizationService.rolesInStructureForSession(principal, structureID);

		if (actorRoles.stream().noneMatch(role -> RolePermissionPolicy.allows(role, Permission.INVITE_USERS))) {
			throw denied();
		}

		if (employeeID == null) {
			throw denied();
		}

		boolean elevated = actorRoles.contains(UserRole.ADMIN) || actorRoles.contains(UserRole.R_H)
				|| actorRoles.contains(UserRole.SUPER_ADMIN);

		if (!elevated && (!actorRoles.contains(UserRole.SECRETARY)
				&& (!actorRoles.contains(UserRole.MANAGER)
						|| !responsibilityStore.isResponsibleFor(actorUserId, employeeID, structureID)))) {
			throw denied();
		}

		if (employeeID != null && (employeeStore.findByIdForUpdate(employeeID)
				.filter(employee -> structureID.equals(employee.getStructureID())).isEmpty()
				|| store.findByEmployeeID(employeeID).isPresent())) {
			throw denied();
		}

		UUID tenantID = structure.getTenantID();
		UUID accountID = UUID.randomUUID();
		UserAccount account = employeeID == null
				? UserAccount.pending(accountID, lastName, firstName, email, phoneNumber)
				: UserAccount.pending(accountID, employeeID, lastName, firstName, email, phoneNumber);
		TenantMembership membership = new TenantMembership(accountID, tenantID);

		store.createAccount(account);
		tenantMembershipStore.save(membership);

		AuditTarget userTarget = new AuditTarget(AuditTargetType.ACCOUNT, accountID);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(userTarget, structureTarget);

		auditService.record(AuditAction.ACCOUNT_CREATE, AuditResult.SUCCESS, targets);

		return accountID;
	}

	private AccessDeniedException denied() {
		return new AccessDeniedException("Création du compte impossible ou non autorisée.");
	}
}
