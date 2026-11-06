
package fr.gestionplus.server.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.RoleHierarchyPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Service
public class AccountManagementService {

	private final AccountStore store;
	private final AuthorizationService authorizationService;
	private final StructureMembershipStore structureMembershipStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final RoleHierarchyPolicy roleHierarchyPolicy;
	private final AuditService auditService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantMembershipStore tenantMembershipStore;

	public AccountManagementService(AuthorizationService authorizationService, AccountStore store,
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
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findByIdForUpdate(structureID);

		if (structure.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		if (!authorizationService.canInStructure(actorUserId, structureID, Permission.MANAGE_USERS))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<StructureMembership> targetStructureMembership = structureMembershipStore
				.findByUserAndStructure(targetUserId, structureID);

		if (targetStructureMembership.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<UserAccount> targetAccount = store.findById(targetUserId);

		if (targetAccount.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		if (targetStructureMembership.get().getRoles().contains(UserRole.ADMIN)
				|| targetAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<UserAccount> actorAccount = store.findById(actorUserId);

		if (actorAccount.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<StructureMembership> actorStructureMembership = structureMembershipStore
				.findByUserAndStructure(actorUserId, structureID);

		if (actorStructureMembership.isEmpty() && !actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		if (!actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN)) {

			boolean allow = roleHierarchyPolicy.isStrictlyHigher(actorStructureMembership.get().getRoles(),
					targetStructureMembership.get().getRoles());

			if (!allow)
				throw new AccessDeniedException("Vous ne paserez paaaaas !!!");
		}

		structureMembershipStore.deleteByUserAndStructure(targetUserId, structureID);

		AuditTarget userTarget = new AuditTarget(AuditTargetType.ACCOUNT, targetUserId);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(userTarget, structureTarget);

		auditService.record(AuditAction.STRUCTURE_ACCESS_REVOKE, AuditResult.SUCCESS, targets);
	}

	@Transactional
	public void transferStructureAdmin(UUID structureID, UUID newAdminUserID) {

		UUID actorID = authenticatedUserProvider.getCurrentUserID();

		if (structureID == null || newAdminUserID == null)
			throw new AccessDeniedException("Identifiants non reconnus.");

		Optional<OrganizationalStructure> targetedStructure = organizationalStructureStore
				.findByIdForUpdate(structureID);

		if (targetedStructure.isEmpty())
			throw new AccessDeniedException("Cette structure n'existe pas.");

		requireAuthorizedSuperAdmin(actorID, structureID);

		Optional<StructureMembership> oldAdminMembership = structureMembershipStore.findAdminByStructure(structureID);

		if (oldAdminMembership.isEmpty())
			throw new AccessDeniedException("Aucun administrateur actuel trouvé pour cette structure.");

		requireActiveAccount(newAdminUserID);

		if (newAdminUserID.equals(oldAdminMembership.get().getUserID()))
			throw new AccessDeniedException("Cette personne est déjà l'administrateur de cette structure.");

		UUID tenantID = targetedStructure.get().getTenantID();

		requireTenantMembership(newAdminUserID, tenantID);

		structureMembershipStore.deleteByUserAndStructure(oldAdminMembership.get().getUserID(), structureID);

		StructureMembership newMembership = new StructureMembership(newAdminUserID, structureID,
				Set.of(UserRole.ADMIN));

		structureMembershipStore.save(newMembership);

		AuditTarget oldAdminTarget = new AuditTarget(AuditTargetType.ACCOUNT, oldAdminMembership.get().getUserID());
		AuditTarget newAdminTarget = new AuditTarget(AuditTargetType.ACCOUNT, newAdminUserID);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(oldAdminTarget, newAdminTarget, structureTarget);

		auditService.record(AuditAction.STRUCTURE_ADMIN_TRANSFER, AuditResult.SUCCESS, targets);
	}

	@Transactional(readOnly = true)
	public List<StructureMembership> findOtherAdminMemberships(UUID structureID, UUID newAdminUserID) {

		UUID actorID = authenticatedUserProvider.getCurrentUserID();

		if (actorID == null || newAdminUserID == null || structureID == null)
			throw new NullPointerException();

		Optional<OrganizationalStructure> targetedStructure = organizationalStructureStore.findById(structureID);

		if (targetedStructure.isEmpty())
			throw new AccessDeniedException("Cette structure n'existe pas.");

		requireAuthorizedSuperAdmin(actorID, structureID);

		UUID tenantID = targetedStructure.get().getTenantID();

		requireTenantMembership(newAdminUserID, tenantID);
		requireActiveAccount(newAdminUserID);

		List<StructureMembership> structureMembershipList = structureMembershipStore
				.findAdminMembershipsByUserAndTenant(newAdminUserID, tenantID);

		List<StructureMembership> structureMemberships = new ArrayList<>();

		for (StructureMembership membership : structureMembershipList) {

			if (!membership.getStructureID().equals(structureID))
				structureMemberships.add(membership);
		}

		return structureMemberships;
	}

	private void requireAuthorizedSuperAdmin(UUID actorID, UUID structureID) {

		Optional<UserAccount> actorAccount = store.findById(actorID);

		if (actorAccount.isEmpty() || actorAccount.get().getStatu() != UserAccountStatus.ACTIVE
				|| !actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException(
					"Vous ne disposez pas des autorisations nécessaires pour effectuer cette action.");

		boolean can = authorizationService.canInStructure(actorID, structureID, Permission.MANAGE_USERS);

		if (!can)
			throw new AccessDeniedException(
					"Vous ne disposez pas des autorisations nécessaires pour effectuer cette action.");
	}

	private void requireActiveAccount(UUID userID) {

		Optional<UserAccount> account = store.findById(userID);

		if (account.isEmpty() || account.get().getStatu() != UserAccountStatus.ACTIVE)
			throw new AccessDeniedException("Ce compte utilisateur n'existe pas ou n'est pas actif.");
	}

	private void requireTenantMembership(UUID userID, UUID tenantID) {

		if (tenantMembershipStore.findByUserAndTenant(userID, tenantID).isEmpty())
			throw new AccessDeniedException("Cette personne n'est pas rattachée à l'entreprise concernée.");
	}
}
