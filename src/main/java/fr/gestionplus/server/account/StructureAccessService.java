package fr.gestionplus.server.account;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.RoleHierarchyPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Service
public class StructureAccessService {

	private final AccountStore store;
	private final AuthorizationService authorizationService;
	private final StructureMembershipStore structureMembershipStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final RoleHierarchyPolicy roleHierarchyPolicy;
	private final AuditService auditService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantMembershipStore tenantMembershipStore;

	public StructureAccessService(AuthorizationService authorizationService, AccountStore store,
			StructureMembershipStore structureMembershipStore, AuthenticatedUserProvider authenticatedUserProvider,
			AuditService auditService, RoleHierarchyPolicy roleHierarchyPolicy,
			OrganizationalStructureStore organizationalStructureStore, TenantMembershipStore tenantMembershipStore) {

		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.store = Objects.requireNonNull(store);
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.roleHierarchyPolicy = Objects.requireNonNull(roleHierarchyPolicy);
		this.auditService = Objects.requireNonNull(auditService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantMembershipStore = Objects.requireNonNull(tenantMembershipStore);
	}

	@Transactional
	public void revokeStructureAccess(UUID targetUserId, UUID structureID) {

		UUID actorUserId = authenticatedUserProvider.getCurrentUserID();

		if (actorUserId == null || targetUserId == null || structureID == null)
			throw new NullPointerException();

		if (actorUserId.equals(targetUserId))
			throw new AccessDeniedException("Vous ne pouvez pas révoquer votre propre accès.");

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findByIdForUpdate(structureID);

		if (structure.isEmpty())
			throw new AccessDeniedException("Révocation impossible ou non autorisée.");

		if (!authorizationService.canInStructure(actorUserId, structureID, Permission.MANAGE_USERS))
			throw new AccessDeniedException("Vous ne disposez pas des autorisations nécessaires.");

		Optional<StructureMembership> targetStructureMembership = structureMembershipStore
				.findByUserAndStructure(targetUserId, structureID);

		if (targetStructureMembership.isEmpty())
			throw new AccessDeniedException("Révocation impossible ou non autorisée.");

		Optional<UserAccount> targetAccount = store.findById(targetUserId);

		if (targetAccount.isEmpty())
			throw new AccessDeniedException("Révocation impossible ou non autorisée.");

		if (targetStructureMembership.get().getRoles().contains(UserRole.ADMIN)
				|| targetAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException("Ce compte bénéficie d'une protection contre la révocation directe.");

		Optional<UserAccount> actorAccount = store.findById(actorUserId);

		if (actorAccount.isEmpty())
			throw new AccessDeniedException("Révocation impossible ou non autorisée.");

		Optional<StructureMembership> actorStructureMembership = structureMembershipStore
				.findByUserAndStructure(actorUserId, structureID);

		if (actorStructureMembership.isEmpty() && !actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException("Vous ne disposez pas des autorisations nécessaires.");

		if (!actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN)) {

			boolean allow = roleHierarchyPolicy.isStrictlyHigher(actorStructureMembership.get().getRoles(),
					targetStructureMembership.get().getRoles());

			if (!allow)
				throw new AccessDeniedException("Votre niveau d'autorité ne permet pas cette révocation.");
		}

		structureMembershipStore.deleteByUserAndStructure(targetUserId, structureID);

		AuditTarget userTarget = new AuditTarget(AuditTargetType.ACCOUNT, targetUserId);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(userTarget, structureTarget);

		auditService.record(AuditAction.STRUCTURE_ACCESS_REVOKE, AuditResult.SUCCESS, targets,
				AuditMetadata.rolesRevoked(targetStructureMembership.get().getRoles()));
	}

	@Transactional
	public void addStructureAccess(UUID targetUserId, UUID structureID, Set<UserRole> roles) {

		UUID actorUserId = authenticatedUserProvider.getCurrentUserID();

		if (actorUserId == null || targetUserId == null || structureID == null || roles == null || roles.isEmpty())
			throw new AccessDeniedException("Demande de rattachement invalide.");

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findByIdForUpdate(structureID);

		if (structure.isEmpty()
				|| !authorizationService.canInStructure(actorUserId, structureID, Permission.MANAGE_USERS)
				|| !authorizationService.canInStructure(actorUserId, structureID, Permission.MANAGE_USER_ROLES))
			throw new AccessDeniedException("Vous ne disposez pas des autorisations nécessaires.");

		Optional<UserAccount> targetAccount = store.findById(targetUserId);

		if (targetAccount.isEmpty())
			throw new AccessDeniedException("Rattachement impossible ou non autorisé.");

		if (tenantMembershipStore.findByUserAndTenant(targetUserId, structure.get().getTenantID()).isEmpty())
			throw new AccessDeniedException("Rattachement impossible ou non autorisé.");

		if (structureMembershipStore.findByUserAndStructure(targetUserId, structureID).isPresent())
			throw new AccessDeniedException("Cet employé est déjà rattaché à cette structure.");

		Optional<UserAccount> actorAccount = store.findById(actorUserId);
		Set<UserRole> actorRoles;

		if (actorAccount.isEmpty())
			throw new AccessDeniedException("Rattachement impossible ou non autorisé.");

		if (actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN)) {
			actorRoles = actorAccount.get().getRoles();

		} else {
			Optional<StructureMembership> actorStructure = structureMembershipStore.findByUserAndStructure(actorUserId,
					structureID);

			if (actorStructure.isEmpty())
				throw new AccessDeniedException("Rattachement impossible ou non autorisé.");

			actorRoles = actorStructure.get().getRoles();
		}

		Set<UserRole> requestedRoles = new HashSet<>(roles);

		if (!roleHierarchyPolicy.canAssignRoles(actorRoles, requestedRoles))
			throw new AccessDeniedException("Rattachement impossible ou non autorisé.");

		StructureMembership structureMembership = new StructureMembership(targetUserId, structureID, requestedRoles);

		structureMembershipStore.save(structureMembership);

		AuditTarget userTarget = new AuditTarget(AuditTargetType.ACCOUNT, targetUserId);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(userTarget, structureTarget);

		auditService.record(AuditAction.STRUCTURE_ACCESS_GRANT, AuditResult.SUCCESS, targets,
				AuditMetadata.rolesGranted(structureMembership.getRoles()));
	}

}
