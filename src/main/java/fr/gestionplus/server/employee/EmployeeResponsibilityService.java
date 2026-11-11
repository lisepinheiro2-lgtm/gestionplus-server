package fr.gestionplus.server.employee;

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
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructureStore;

@Service
public class EmployeeResponsibilityService {

	private final EmployeeStore employeeStore;
	private final EmployeeResponsibilityStore responsibilityStore;
	private final OrganizationalStructureStore structureStore;
	private final AuthorizationService authorizationService;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuditService auditService;

	public EmployeeResponsibilityService(EmployeeStore employeeStore, EmployeeResponsibilityStore responsibilityStore,
			OrganizationalStructureStore structureStore, AuthorizationService authorizationService,
			AuthenticatedUserProvider authenticatedUserProvider, AuditService auditService) {
		this.employeeStore = employeeStore;
		this.responsibilityStore = responsibilityStore;
		this.structureStore = structureStore;
		this.authorizationService = authorizationService;
		this.authenticatedUserProvider = authenticatedUserProvider;
		this.auditService = auditService;
	}

	@AuditedOperation(value = AuditAction.EMPLOYEE_MANAGER_CHANGE, structure = 0, employee = 1, account = 2)
	@Transactional
	public void changeResponsibleManager(UUID structureID, UUID employeeID, UUID managerUserID) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		UUID actorUserID = principal.getUserID();

		if (structureID == null || employeeID == null || managerUserID == null
				|| structureStore.findByIdForUpdate(structureID).isEmpty()) {
			throw new AccessDeniedException("Changement de responsable impossible ou non autorisé.");
		}

		Set<UserRole> actorRoles = authorizationService.rolesInStructureForSession(principal, structureID);

		if (!actorRoles.contains(UserRole.ADMIN) && !actorRoles.contains(UserRole.R_H)
				&& !actorRoles.contains(UserRole.SUPER_ADMIN)) {
			throw new AccessDeniedException("Changement de responsable impossible ou non autorisé.");
		}

		boolean scopedEmployee = employeeStore.findByIdForUpdate(employeeID)
				.filter(employee -> structureID.equals(employee.getStructureID())).isPresent();

		if (!scopedEmployee
				|| !authorizationService.rolesInStructure(managerUserID, structureID).contains(UserRole.MANAGER)
				|| (actorUserID.equals(managerUserID) && actorRoles.contains(UserRole.MANAGER))) {
			throw new AccessDeniedException("Changement de responsable impossible ou non autorisé.");
		}

		responsibilityStore.assignManager(employeeID, structureID, managerUserID);
		auditService.record(AuditAction.EMPLOYEE_MANAGER_CHANGE, AuditResult.SUCCESS,
				Set.of(new AuditTarget(AuditTargetType.EMPLOYEE, employeeID),
						new AuditTarget(AuditTargetType.ACCOUNT, managerUserID),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID)));
	}
}
