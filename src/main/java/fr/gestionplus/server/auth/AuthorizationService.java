package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Service
public class AuthorizationService {

	private final StructureMembershipStore structureMembershipStore;
	private final AccountStore accountStore;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantOrganizationStore tenantOrganizationStore;
	private final TenantMembershipStore tenantMembershipStore;

	public AuthorizationService(StructureMembershipStore structureMembershipStore, AccountStore accountStore,
			OrganizationalStructureStore organizationalStructureStore,
			TenantOrganizationStore tenantOrganizationStore, TenantMembershipStore tenantMembershipStore) {
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.accountStore = Objects.requireNonNull(accountStore);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantOrganizationStore = Objects.requireNonNull(tenantOrganizationStore);
		this.tenantMembershipStore = Objects.requireNonNull(tenantMembershipStore);
	}

	public boolean canInStructure(UUID actorUserID, UUID structureID, Permission permission) {

		return permission != null && rolesInStructure(actorUserID, structureID).stream()
				.anyMatch(role -> RolePermissionPolicy.allows(role, permission));
	}

	public boolean hasTenantMembership(UUID userID, UUID tenantID) {
		return tenantMembershipStore.findByUserAndTenant(userID, tenantID)
				.filter(membership -> userID.equals(membership.getUserID())
						&& tenantID.equals(membership.getTenantID()))
				.isPresent();
	}

	public boolean isDesignatedSuperAdmin(UUID userID) {
		return tenantOrganizationStore.findBySuperAdminUserID(userID)
				.filter(tenant -> userID.equals(tenant.getSuperAdminUserID())).isPresent();
	}

	public boolean requiresMfa(UUID userID) {
		return isDesignatedSuperAdmin(userID)
				|| RoleMfaPolicy.requiresAny(structureMembershipStore.findEffectiveLocalRoles(userID));
	}

	public Set<UserRole> rolesInStructure(UUID actorUserID, UUID structureID) {
		return rolesInStructure(actorUserID, structureID, null);
	}

	public Set<UserRole> rolesInStructureForSession(UserAccount principal, UUID structureID) {
		return rolesInStructure(principal.getUserID(), structureID, principal.getSessionVersion());
	}

	private Set<UserRole> rolesInStructure(UUID actorUserID, UUID structureID, Long sessionVersion) {

		if (actorUserID == null || structureID == null) {
			return Set.of();
		}

		Optional<UserAccount> account = accountStore.findById(actorUserID);

		if (account.isEmpty() || !actorUserID.equals(account.get().getUserID())) {
			return Set.of();
		}

		UserAccount userAccount = account.get();

		if (userAccount.getStatu() != UserAccountStatus.ACTIVE
				|| (sessionVersion != null && userAccount.getSessionVersion() != sessionVersion)) {
			return Set.of();
		}

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findById(structureID);

		if (structure.isEmpty() || !structureID.equals(structure.get().getStructureID())) {
			return Set.of();
		}

		UUID tenantID = structure.get().getTenantID();
		Optional<TenantOrganization> tenantOrganization = tenantOrganizationStore.findById(tenantID);

		if (tenantOrganization.isEmpty() || !tenantID.equals(tenantOrganization.get().getTenantID())
				|| !hasTenantMembership(actorUserID, tenantID)) {
			return Set.of();
		}

		if (tenantOrganization.get().getSuperAdminUserID().equals(actorUserID)) {
			return Set.of(UserRole.SUPER_ADMIN);
		}

		if (isDesignatedSuperAdmin(actorUserID)) {
			return Set.of();
		}

		Optional<StructureMembership> userAndStructure = structureMembershipStore.findByUserAndStructure(actorUserID,
				structureID);

		if (userAndStructure.isEmpty()) {
			return Set.of();
		}

		StructureMembership membership = userAndStructure.get();

		if (!actorUserID.equals(membership.getUserID()) || !structureID.equals(membership.getStructureID())
				|| membership.getRoles().contains(UserRole.SUPER_ADMIN)) {
			return Set.of();
		}

		return membership.getRoles();
	}
}
