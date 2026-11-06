package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.GestionAuthenticationProvider;
import fr.gestionplus.server.auth.HttpSecurityConfig;
import fr.gestionplus.server.auth.RoleHierarchyPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

class AccountManagementServiceTest {

	private final UUID actorUserId = UUID.randomUUID();
	private final UUID targetUserId = UUID.randomUUID();
	private final UUID structureID = UUID.randomUUID();
	private final UUID tenantID = UUID.randomUUID();
	private final AccountStore store = mock(AccountStore.class);
	private final StructureMembershipStore structureMembershipStore = mock(StructureMembershipStore.class);
	private final OrganizationalStructureStore organizationalStructureStore = mock(OrganizationalStructureStore.class);
	private final TenantOrganizationStore tenantOrganizationStore = mock(TenantOrganizationStore.class);
	private final AuditService auditService = mock(AuditService.class);
	private final AuthenticatedUserProvider authenticatedUserProvider = new AuthenticatedUserProvider();
	private final RoleHierarchyPolicy roleHierarchyPolicy = new RoleHierarchyPolicy(
			new HttpSecurityConfig(mock(GestionAuthenticationProvider.class)).roleHierarchy());
	private final TenantMembershipStore tenantMembershipStore = mock(TenantMembershipStore.class);

	private AuthorizationService authorizationService;
	private AccountManagementService service;

	@BeforeEach
	void setUp() {
		SecurityContextHolder.clearContext();
		authorizationService = new AuthorizationService(structureMembershipStore, store, organizationalStructureStore,
				tenantOrganizationStore);
		service = new AccountManagementService(authorizationService, store, structureMembershipStore,
				authenticatedUserProvider, auditService, roleHierarchyPolicy, organizationalStructureStore,
				tenantMembershipStore);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private UserAccount account(UUID userID, UserAccountStatus statu, Set<UserRole> roles) {
		UserAccount account = new UserAccount(userID, "test_" + userID, statu, roles, UUID.randomUUID());
		when(store.findById(userID)).thenReturn(Optional.of(account));
		return account;
	}

	private void authenticate(UserAccount account) {
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
	}

	private void prepare(Set<UserRole> actorRoles, Set<UserRole> targetRoles) {
		UserAccount actorUser = account(actorUserId, UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		account(targetUserId, UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));
		authenticate(actorUser);

		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Structure", tenantID);
		when(organizationalStructureStore.findByIdForUpdate(structureID)).thenReturn(Optional.of(structure));
		when(organizationalStructureStore.findById(structureID)).thenReturn(Optional.of(structure));
		when(tenantOrganizationStore.findById(tenantID))
				.thenReturn(Optional.of(new TenantOrganization(tenantID, "Entreprise", UUID.randomUUID())));
		when(structureMembershipStore.findByUserAndStructure(actorUserId, structureID))
				.thenReturn(Optional.of(new StructureMembership(actorUserId, structureID, actorRoles)));
		when(structureMembershipStore.findByUserAndStructure(targetUserId, structureID))
				.thenReturn(Optional.of(new StructureMembership(targetUserId, structureID, targetRoles)));
	}

	private void prepareSuperAdmin(Set<UserRole> targetRoles) {
		prepare(Set.of(), targetRoles);
		UserAccount actorUser = account(actorUserId, UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN));
		authenticate(actorUser);
		when(tenantOrganizationStore.findById(tenantID))
				.thenReturn(Optional.of(new TenantOrganization(tenantID, "Entreprise", actorUserId)));
		when(structureMembershipStore.findByUserAndStructure(actorUserId, structureID)).thenReturn(Optional.empty());
	}

	private void assertNoRevocation() {
		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(store, never()).disableAccount(any());
		verify(store, never()).createAccount(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	private void assertSuccessfulRevocation() {
		InOrder order = inOrder(organizationalStructureStore, structureMembershipStore, auditService);
		order.verify(organizationalStructureStore).findByIdForUpdate(structureID);
		order.verify(structureMembershipStore).deleteByUserAndStructure(targetUserId, structureID);
		order.verify(auditService).record(AuditAction.STRUCTURE_ACCESS_REVOKE, AuditResult.SUCCESS,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, targetUserId),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID)));
		verify(structureMembershipStore, times(1)).deleteByUserAndStructure(any(), any());
		verify(store, never()).disableAccount(any());
		verify(store, never()).createAccount(any());
		verifyNoMoreInteractions(auditService);
	}

	@Test
	void rejectsNullDependencies() {
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(null, store, structureMembershipStore, authenticatedUserProvider,
						auditService, roleHierarchyPolicy, organizationalStructureStore, tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, null, structureMembershipStore,
						authenticatedUserProvider, auditService, roleHierarchyPolicy, organizationalStructureStore,
						tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, store, null, authenticatedUserProvider,
						auditService, roleHierarchyPolicy, organizationalStructureStore, tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, store, structureMembershipStore, null,
						auditService, roleHierarchyPolicy, organizationalStructureStore, tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, store, structureMembershipStore,
						authenticatedUserProvider, null, roleHierarchyPolicy, organizationalStructureStore,
						tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, store, structureMembershipStore,
						authenticatedUserProvider, auditService, null, organizationalStructureStore,
						tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, store, structureMembershipStore,
						authenticatedUserProvider, auditService, roleHierarchyPolicy, null, tenantMembershipStore));
		assertThrows(NullPointerException.class,
				() -> new AccountManagementService(authorizationService, store, structureMembershipStore,
						authenticatedUserProvider, auditService, roleHierarchyPolicy, organizationalStructureStore,
						null));
	}

	@Test
	void rejectsMissingSpringIdentity() {
		assertThrows(AuthenticationCredentialsNotFoundException.class,
				() -> service.revokeStructureAccess(targetUserId, structureID));
		verifyNoInteractions(store, structureMembershipStore, organizationalStructureStore, auditService);
	}

	@Test
	void rejectsUnauthenticatedSpringIdentity() {
		UserAccount actorUser = new UserAccount(actorUserId, "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.SUPER_ADMIN), UUID.randomUUID());
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated(actorUser, null));

		assertThrows(AuthenticationCredentialsNotFoundException.class,
				() -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsUnsupportedSpringPrincipalEvenWithSuperAdminAuthority() {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice",
				null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

		assertThrows(AuthenticationCredentialsNotFoundException.class,
				() -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsNullTargetOrStructure() {
		authenticate(new UserAccount(actorUserId, "alice", UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN),
				UUID.randomUUID()));

		assertThrows(NullPointerException.class, () -> service.revokeStructureAccess(null, structureID));
		assertThrows(NullPointerException.class, () -> service.revokeStructureAccess(targetUserId, null));
		verifyNoInteractions(store, structureMembershipStore, organizationalStructureStore, auditService);
	}

	@Test
	void rejectsSelfRevocationEvenForSuperAdmin() {
		prepareSuperAdmin(Set.of(UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(actorUserId, structureID));
		verifyNoInteractions(store, structureMembershipStore, organizationalStructureStore, auditService);
	}

	@Test
	void rejectsUnknownStructure() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		when(organizationalStructureStore.findByIdForUpdate(structureID)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsUnknownActorAccount() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		when(store.findById(actorUserId)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = { "DISABLED", "PENDING_ACTIVATION" })
	void reloadsActorStatusInsteadOfTrustingActiveSpringPrincipal(UserAccountStatus statu) {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		account(actorUserId, statu, Set.of(UserRole.SUPER_ADMIN));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void ignoresForgedOrStaleSpringRolesAndAuthorities() {
		prepare(Set.of(UserRole.EMPLOYEE), Set.of(UserRole.EMPLOYEE));
		UserAccount actorUser = new UserAccount(actorUserId, "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.SUPER_ADMIN, UserRole.ADMIN), UUID.randomUUID());
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken
				.authenticated(actorUser, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsMissingActorMembershipDespiteGlobalAdminRole() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		account(actorUserId, UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN));
		when(structureMembershipStore.findByUserAndStructure(actorUserId, structureID)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsRightsHeldOnlyInAnotherStructure() {
		prepare(Set.of(UserRole.EMPLOYEE), Set.of(UserRole.EMPLOYEE));
		UUID structureB = UUID.randomUUID();
		when(structureMembershipStore.findByUserAndStructure(actorUserId, structureB))
				.thenReturn(Optional.of(new StructureMembership(actorUserId, structureB, Set.of(UserRole.ADMIN))));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		verify(structureMembershipStore, never()).findByUserAndStructure(actorUserId, structureB);
		assertNoRevocation();
	}

	@Test
	void secretaryCannotRevokeEvenWhenHigherThanEmployee() {
		prepare(Set.of(UserRole.SECRETARY), Set.of(UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsUnknownTargetMembership() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		when(structureMembershipStore.findByUserAndStructure(targetUserId, structureID)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsUnknownTargetAccount() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		when(store.findById(targetUserId)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void superAdminCannotRevokeAdminIndividuallyEvenWithOtherTargetRoles() {
		prepareSuperAdmin(Set.of(UserRole.ADMIN, UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void adminCannotRevokeAnotherAdmin() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.ADMIN));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void protectsGlobalSuperAdminEvenWithOrdinaryStructureRole() {
		prepareSuperAdmin(Set.of(UserRole.EMPLOYEE));
		account(targetUserId, UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "MANAGER", "SECRETARY", "TEAM_LEAD", "EMPLOYEE" })
	void rhCanRevokeStrictlyLowerStructureRoles(UserRole targetRole) {
		prepare(Set.of(UserRole.R_H), Set.of(targetRole));

		service.revokeStructureAccess(targetUserId, structureID);

		assertSuccessfulRevocation();
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "TEAM_LEAD", "EMPLOYEE" })
	void managerCanRevokeStrictlyLowerStructureRoles(UserRole targetRole) {
		prepare(Set.of(UserRole.MANAGER), Set.of(targetRole));

		service.revokeStructureAccess(targetUserId, structureID);

		assertSuccessfulRevocation();
	}

	@Test
	void rejectsEqualRoles() {
		prepare(Set.of(UserRole.R_H), Set.of(UserRole.R_H));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void managerCannotRevokeIncomparableSecretary() {
		prepare(Set.of(UserRole.MANAGER), Set.of(UserRole.SECRETARY));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rhCannotRevokeTargetWithAdditionalAccountantRole() {
		prepare(Set.of(UserRole.R_H), Set.of(UserRole.EMPLOYEE, UserRole.ACCOUNTANT));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsTargetWithNoRoles() {
		prepare(Set.of(UserRole.ADMIN), Set.of());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void adminCanRevokeRhAndAccountantUsingStructureRoles() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.R_H, UserRole.ACCOUNTANT));

		service.revokeStructureAccess(targetUserId, structureID);

		assertSuccessfulRevocation();
	}

	@Test
	void superAdminCanRevokeOrdinaryAccessInOwnTenantWithoutMembership() {
		prepareSuperAdmin(Set.of(UserRole.R_H, UserRole.ACCOUNTANT));

		service.revokeStructureAccess(targetUserId, structureID);

		assertSuccessfulRevocation();
	}

	@Test
	void superAdminCannotRevokeAccessInAnotherTenantEvenWithLocalAdminMembership() {
		prepareSuperAdmin(Set.of(UserRole.EMPLOYEE));
		when(tenantOrganizationStore.findById(tenantID))
				.thenReturn(Optional.of(new TenantOrganization(tenantID, "Autre entreprise", UUID.randomUUID())));
		when(structureMembershipStore.findByUserAndStructure(actorUserId, structureID))
				.thenReturn(Optional.of(new StructureMembership(actorUserId, structureID, Set.of(UserRole.ADMIN))));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void preservesAccountAndAccessToOtherStructures() {
		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));
		UUID structureB = UUID.randomUUID();
		when(structureMembershipStore.findByUserAndStructure(targetUserId, structureB))
				.thenReturn(Optional.of(new StructureMembership(targetUserId, structureB, Set.of(UserRole.ADMIN))));

		service.revokeStructureAccess(targetUserId, structureID);

		assertSuccessfulRevocation();
		verify(structureMembershipStore, never()).deleteByUserAndStructure(targetUserId, structureB);
	}

	@Test
	void rejectsUnknownTenant() {
		prepareSuperAdmin(Set.of(UserRole.EMPLOYEE));
		when(tenantOrganizationStore.findById(tenantID)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void rejectsSuperAdminInjectedAsStructureRole() {
		prepare(Set.of(UserRole.SUPER_ADMIN, UserRole.ADMIN), Set.of(UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetUserId, structureID));
		assertNoRevocation();
	}

	@Test
	void superAdminCanTransferStructureAdminInOwnTenant() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantMembershipStore.findByUserAndTenant(targetUserId, tenantID))
				.thenReturn(Optional.of(new TenantMembership(targetUserId, tenantID)));

		when(structureMembershipStore.findByUserAndStructure(targetUserId, structureID)).thenReturn(Optional.empty());

		UUID oldAdminUserID = UUID.randomUUID();
		account(oldAdminUserID, UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));

		when(structureMembershipStore.findAdminByStructure(structureID))
				.thenReturn(Optional.of(new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN))));

		service.transferStructureAdmin(structureID, targetUserId);

		verify(organizationalStructureStore).findByIdForUpdate(structureID);
		verify(structureMembershipStore).save(argThat(membership -> membership.getUserID().equals(targetUserId)
				&& membership.getStructureID().equals(structureID)
				&& membership.getRoles().equals(Set.of(UserRole.ADMIN))));
		verify(structureMembershipStore).deleteByUserAndStructure(oldAdminUserID, structureID);
		verify(structureMembershipStore, times(1)).deleteByUserAndStructure(any(), any());
		verify(store, never()).disableAccount(any());
		verify(auditService).record(eq(AuditAction.STRUCTURE_ADMIN_TRANSFER), eq(AuditResult.SUCCESS),
				eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, oldAdminUserID),
						new AuditTarget(AuditTargetType.ACCOUNT, targetUserId),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID))));
	}

	@Test
	void rejectsAdminTransferOutsideTenant() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		UUID oldAdminUserID = UUID.randomUUID();
		account(oldAdminUserID, UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE));

		when(structureMembershipStore.findAdminByStructure(structureID))
				.thenReturn(Optional.of(new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN))));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));
		verify(tenantMembershipStore).findByUserAndTenant(targetUserId, tenantID);
		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void adminCannotTransferStructureAdmin() {

		prepare(Set.of(UserRole.ADMIN), Set.of(UserRole.R_H));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));

		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void superAdminCannotTransferAdminInAnotherTenant() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantOrganizationStore.findById(tenantID))
				.thenReturn(Optional.of(new TenantOrganization(tenantID, "Autre entreprise", UUID.randomUUID())));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));

		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void rejectsTransferToDisabledAccount() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		UUID oldAdminUserID = UUID.randomUUID();

		when(structureMembershipStore.findAdminByStructure(structureID))
				.thenReturn(Optional.of(new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN))));

		account(targetUserId, UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));

		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void rejectsTransferToUnknownAccount() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		UUID oldAdminUserID = UUID.randomUUID();

		when(structureMembershipStore.findAdminByStructure(structureID))
				.thenReturn(Optional.of(new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN))));
		when(store.findById(targetUserId)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));

		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void rejectsTransferToCurrentAdmin() {

		prepareSuperAdmin(Set.of(UserRole.ADMIN));

		when(structureMembershipStore.findAdminByStructure(structureID))
				.thenReturn(Optional.of(new StructureMembership(targetUserId, structureID, Set.of(UserRole.ADMIN))));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));

		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void rejectsDisabledSuperAdminDespiteAuthenticatedSession() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		account(actorUserId, UserAccountStatus.DISABLED, Set.of(UserRole.SUPER_ADMIN));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(structureID, targetUserId));

		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verify(structureMembershipStore, never()).save(any());
		verify(auditService, never()).record(any(), eq(AuditResult.SUCCESS), anySet());
	}

	@Test
	void findsOtherAdminMembershipsWithoutDestination() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantMembershipStore.findByUserAndTenant(targetUserId, tenantID))
				.thenReturn(Optional.of(new TenantMembership(targetUserId, tenantID)));

		UUID structureB = UUID.randomUUID();
		UUID structureC = UUID.randomUUID();

		when(structureMembershipStore.findAdminMembershipsByUserAndTenant(targetUserId, tenantID))
				.thenReturn(List.of(new StructureMembership(targetUserId, structureID, Set.of(UserRole.ADMIN)),
						new StructureMembership(targetUserId, structureB, Set.of(UserRole.ADMIN)),
						new StructureMembership(targetUserId, structureC, Set.of(UserRole.ADMIN))));

		List<StructureMembership> result = service.findOtherAdminMemberships(structureID, targetUserId);

		assertEquals(2, result.size());
		assertEquals(Set.of(structureB, structureC),
				Set.of(result.get(0).getStructureID(), result.get(1).getStructureID()));

		verify(structureMembershipStore, never()).save(any());
		verify(structureMembershipStore, never()).deleteByUserAndStructure(any(), any());
		verifyNoInteractions(auditService);
	}

	@Test
	void returnsEmptyWhenSuccessorHasNoOtherAdminMemberships() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantMembershipStore.findByUserAndTenant(targetUserId, tenantID))
				.thenReturn(Optional.of(new TenantMembership(targetUserId, tenantID)));
		when(structureMembershipStore.findAdminMembershipsByUserAndTenant(targetUserId, tenantID))
				.thenReturn(List.of());

		List<StructureMembership> result = service.findOtherAdminMemberships(structureID, targetUserId);

		assertTrue(result.isEmpty());
		verifyNoInteractions(auditService);
	}

	@Test
	void rejectsOtherAdminConsultationOutsideTenant() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantOrganizationStore.findById(tenantID))
				.thenReturn(Optional.of(new TenantOrganization(tenantID, "Autre entreprise", UUID.randomUUID())));

		assertThrows(AccessDeniedException.class, () -> service.findOtherAdminMemberships(structureID, targetUserId));
		verify(structureMembershipStore, never()).findAdminMembershipsByUserAndTenant(any(), any());
		verifyNoInteractions(auditService);
	}

	@Test
	void rejectsOtherAdminConsultationWithoutTenantMembership() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantMembershipStore.findByUserAndTenant(targetUserId, tenantID)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.findOtherAdminMemberships(structureID, targetUserId));
		verify(structureMembershipStore, never()).findAdminMembershipsByUserAndTenant(any(), any());
	}

	@Test
	void rejectsOtherAdminConsultationForDisabledSuccessor() {

		prepareSuperAdmin(Set.of(UserRole.R_H));

		when(tenantMembershipStore.findByUserAndTenant(targetUserId, tenantID))
				.thenReturn(Optional.of(new TenantMembership(targetUserId, tenantID)));

		account(targetUserId, UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.findOtherAdminMemberships(structureID, targetUserId));
		verify(structureMembershipStore, never()).findAdminMembershipsByUserAndTenant(any(), any());
	}

}
