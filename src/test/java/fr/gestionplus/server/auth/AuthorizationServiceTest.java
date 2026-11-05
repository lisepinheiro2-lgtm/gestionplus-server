package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

class AuthorizationServiceTest {

	private final UUID userID = UUID.randomUUID();
	private final UUID structureID = UUID.randomUUID();
	private final UUID tenantID = UUID.randomUUID();
	private final StructureMembershipStore structureMembershipStore = mock(StructureMembershipStore.class);
	private final AccountStore accountStore = mock(AccountStore.class);
	private final OrganizationalStructureStore organizationalStructureStore = mock(OrganizationalStructureStore.class);
	private final TenantOrganizationStore tenantOrganizationStore = mock(TenantOrganizationStore.class);

	private AuthorizationService service;

	@BeforeEach
	void setUp() {
		service = new AuthorizationService(structureMembershipStore, accountStore, organizationalStructureStore,
				tenantOrganizationStore);
	}

	private UserAccount account(UserAccountStatus statu, Set<UserRole> roles) {
		UserAccount account = new UserAccount(userID, "alice", statu, roles, UUID.randomUUID());
		when(accountStore.findById(userID)).thenReturn(Optional.of(account));
		return account;
	}

	private void structure(UUID structureID, UUID tenantID, UUID superAdminUserID) {
		when(organizationalStructureStore.findById(structureID))
				.thenReturn(Optional.of(new OrganizationalStructure(structureID, "Structure", tenantID)));
		when(tenantOrganizationStore.findById(tenantID))
				.thenReturn(Optional.of(new TenantOrganization(tenantID, "Entreprise", superAdminUserID)));
	}

	private void membership(UUID userID, UUID structureID, Set<UserRole> roles) {
		when(structureMembershipStore.findByUserAndStructure(userID, structureID))
				.thenReturn(Optional.of(new StructureMembership(userID, structureID, roles)));
	}

	@Test
	void rejectsNullDependencies() {
		assertThrows(NullPointerException.class,
				() -> new AuthorizationService(null, accountStore, organizationalStructureStore, tenantOrganizationStore));
		assertThrows(NullPointerException.class,
				() -> new AuthorizationService(structureMembershipStore, null, organizationalStructureStore,
						tenantOrganizationStore));
		assertThrows(NullPointerException.class,
				() -> new AuthorizationService(structureMembershipStore, accountStore, null, tenantOrganizationStore));
		assertThrows(NullPointerException.class,
				() -> new AuthorizationService(structureMembershipStore, accountStore, organizationalStructureStore, null));
	}

	@Test
	void rejectsNullParametersInStructure() {
		assertFalse(service.canInStructure(null, structureID, Permission.MANAGE_USERS));
		assertFalse(service.canInStructure(userID, null, Permission.MANAGE_USERS));
		assertFalse(service.canInStructure(userID, structureID, null));
		verifyNoInteractions(accountStore, organizationalStructureStore, tenantOrganizationStore, structureMembershipStore);
	}

	@Test
	void rejectsUnknownAccount() {
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verify(accountStore).findById(userID);
		verifyNoInteractions(organizationalStructureStore, tenantOrganizationStore, structureMembershipStore);
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = { "DISABLED", "PENDING_ACTIVATION" })
	void rejectsInactiveAccountsInStructure(UserAccountStatus statu) {
		account(statu, Set.of(UserRole.SUPER_ADMIN));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verifyNoInteractions(organizationalStructureStore, tenantOrganizationStore, structureMembershipStore);
	}

	@Test
	void rejectsUnknownStructureEvenForSuperAdmin() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verifyNoInteractions(tenantOrganizationStore, structureMembershipStore);
	}

	@Test
	void rejectsUnknownTenantEvenForSuperAdmin() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN));
		when(organizationalStructureStore.findById(structureID))
				.thenReturn(Optional.of(new OrganizationalStructure(structureID, "Structure", tenantID)));

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verify(tenantOrganizationStore).findById(tenantID);
		verifyNoInteractions(structureMembershipStore);
	}

	@Test
	void rejectsUnknownTenantForOrdinaryAdmin() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		when(organizationalStructureStore.findById(structureID))
				.thenReturn(Optional.of(new OrganizationalStructure(structureID, "Structure", tenantID)));
		membership(userID, structureID, Set.of(UserRole.ADMIN));

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void adminCanManageUsersInOwnStructure() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));

		assertTrue(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void globalAdminCannotManageUsersWithEmployeeMembership() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN));
		UUID structureB = UUID.randomUUID();
		UUID superAdminUserID = UUID.randomUUID();
		structure(structureID, tenantID, superAdminUserID);
		structure(structureB, tenantID, superAdminUserID);
		membership(userID, structureID, Set.of(UserRole.ADMIN));
		membership(userID, structureB, Set.of(UserRole.EMPLOYEE));

		assertTrue(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		assertFalse(service.canInStructure(userID, structureB, Permission.MANAGE_USERS));
		assertTrue(service.canInStructure(userID, structureB, Permission.VIEW_OWN_SCHEDULE));
	}

	@Test
	void rejectsMissingMembershipEvenWithGlobalAdminRole() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN));
		structure(structureID, tenantID, UUID.randomUUID());

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verify(structureMembershipStore).findByUserAndStructure(userID, structureID);
	}

	@Test
	void rejectsMembershipBelongingToAnotherUser() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		structure(structureID, tenantID, UUID.randomUUID());
		UUID otherUserID = UUID.randomUUID();
		membership(otherUserID, structureID, Set.of(UserRole.ADMIN));

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verify(structureMembershipStore).findByUserAndStructure(userID, structureID);
		verify(structureMembershipStore, never()).findByUserAndStructure(otherUserID, structureID);
	}

	@Test
	void rejectsMembershipFromAnotherStructure() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		structure(structureID, tenantID, UUID.randomUUID());
		UUID structureA = UUID.randomUUID();
		membership(userID, structureA, Set.of(UserRole.ADMIN));

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verify(structureMembershipStore).findByUserAndStructure(userID, structureID);
		verify(structureMembershipStore, never()).findByUserAndStructure(userID, structureA);
	}

	@Test
	void secretaryAccountantCanViewAccountingWithoutManagingUsersOrSensitiveDocuments() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN));
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, Set.of(UserRole.SECRETARY, UserRole.ACCOUNTANT));

		assertTrue(service.canInStructure(userID, structureID, Permission.VIEW_ACCOUNTING));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USER_ROLES));
		assertFalse(service.canInStructure(userID, structureID, Permission.EDIT_SENSITIVE_DOCUMENT));
		assertTrue(service.canInStructure(userID, structureID, Permission.INVITE_USERS));
		assertTrue(service.canInStructure(userID, structureID, Permission.CREATE_CONTRACT));
		assertTrue(service.canInStructure(userID, structureID, Permission.EDIT_CONTRACT));
	}

	@Test
	void rejectsEmptyMembershipRoles() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN));
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, Set.of());

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
	}

	@ParameterizedTest
	@EnumSource(Permission.class)
	void superAdminCanActOnlyInOwnTenantWithoutMembership(Permission permission) {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN));
		structure(structureID, tenantID, userID);

		assertTrue(service.canInStructure(userID, structureID, permission));
		verifyNoInteractions(structureMembershipStore);
	}

	@Test
	void superAdminCanAccessAnotherStructureOfOwnTenant() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN));
		UUID structureB = UUID.randomUUID();
		structure(structureID, tenantID, userID);
		structure(structureB, tenantID, userID);

		assertTrue(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		assertTrue(service.canInStructure(userID, structureB, Permission.MANAGE_USERS));
	}

	@ParameterizedTest
	@EnumSource(Permission.class)
	void superAdminCannotActInAnotherTenantEvenWithAdminMembership(Permission permission) {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN, UserRole.ADMIN));
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, Set.of(UserRole.ADMIN));

		assertFalse(service.canInStructure(userID, structureID, permission));
		verifyNoInteractions(structureMembershipStore);
	}

	@Test
	void tenantOwnershipWithoutGlobalSuperAdminRoleDoesNotGrantAccess() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		structure(structureID, tenantID, userID);

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void rejectsSuperAdminAsStructureRoleEvenAlongsideAdmin() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		structure(structureID, tenantID, userID);
		membership(userID, structureID, Set.of(UserRole.SUPER_ADMIN, UserRole.ADMIN));

		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		assertFalse(service.canInStructure(userID, structureID, Permission.VIEW_OWN_SCHEDULE));
	}

	@Test
	void reloadsAccountStatusOnEveryAuthorization() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, Set.of(UserRole.ADMIN));
		assertTrue(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));

		account(UserAccountStatus.DISABLED, Set.of(UserRole.ADMIN));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
		verify(accountStore, times(2)).findById(userID);
	}

	@Test
	void reloadsMembershipRolesOnEveryAuthorization() {
		account(UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN));
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, Set.of(UserRole.ADMIN));
		assertTrue(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));

		membership(userID, structureID, Set.of(UserRole.EMPLOYEE));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void cannotModifyUserRoles() {
		UserAccount account = account(UserAccountStatus.ACTIVE, Set.of(UserRole.MANAGER));
		assertThrows(UnsupportedOperationException.class, () -> account.getRoles().add(UserRole.ADMIN));
	}

	@Test
	void modifyingOriginalRolesDoesNotChangeAccountOrStructureRights() {
		Set<UserRole> roles = new HashSet<>();
		roles.add(UserRole.EMPLOYEE);
		UserAccount account = account(UserAccountStatus.ACTIVE, roles);
		structure(structureID, tenantID, UUID.randomUUID());
		membership(userID, structureID, roles);

		roles.add(UserRole.ADMIN);

		assertFalse(account.getRoles().contains(UserRole.ADMIN));
		assertFalse(service.canInStructure(userID, structureID, Permission.MANAGE_USERS));
	}
}
