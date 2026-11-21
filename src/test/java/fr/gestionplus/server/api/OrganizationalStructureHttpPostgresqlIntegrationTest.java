package fr.gestionplus.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

import fr.gestionplus.server.auth.SessionLifetime;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.OrganizationalStructureEntity;
import fr.gestionplus.server.persistence.OrganizationalStructureJpaRepository;
import fr.gestionplus.server.persistence.StructureMembershipEntity;
import fr.gestionplus.server.persistence.StructureMembershipJpaRepository;
import fr.gestionplus.server.persistence.TenantOrganizationEntity;
import fr.gestionplus.server.persistence.TenantOrganizationJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_structure_access_http_test?TC_DAEMON=true",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test",
		"spring.flyway.url=jdbc:tc:postgresql:16-alpine:///gestionplus_structure_access_http_test?TC_DAEMON=true",
		"spring.flyway.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.flyway.user=test", "spring.flyway.password=test", "spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=validate", "gestionplus.security.require-https=false",
		"gestionplus.mfa.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
@AutoConfigureMockMvc
class OrganizationalStructureHttpPostgresqlIntegrationTest extends AuditStorageIntegrationTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private EmployeeJpaRepository employees;
	@Autowired
	private UserAccountJpaRepository accounts;
	@Autowired
	private TenantOrganizationJpaRepository tenants;
	@Autowired
	private OrganizationalStructureJpaRepository structures;
	@Autowired
	private StructureMembershipJpaRepository memberships;
	@Autowired
	private OrganizationalStructureStore structureStore;
	@Autowired
	private StructureMembershipStore membershipStore;
	@Autowired
	private SessionLifetime lifetime;
	@Autowired
	private SessionRegistry sessions;

	@AfterEach
	void clearSessions() {

		for (Object principal : sessions.getAllPrincipals()) {

			for (var session : sessions.getAllSessions(principal, true)) {
				sessions.removeSessionInformation(session.getSessionId());
			}

		}

		SecurityContextHolder.clearContext();
	}

	@Test
	void anonymousRequestsCannotReadTheListOrADetail() throws Exception {
		mvc.perform(get("/api/structures")).andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}"));
		mvc.perform(get("/api/structures/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "EMPLOYEE", "ADMIN", "MANAGER", "SECRETARY", "ACCOUNTANT" })
	void listsOnlyTheCurrentUsersStructuresAndReturnsOnlyTheTwoPublicFields(UserRole role) throws Exception {
		Tenant first = tenant("Fictitious company A");
		Tenant second = tenant("Fictitious company B");
		OrganizationalStructure beta = structure(first, "Beta");
		OrganizationalStructure alpha = structure(first, "Alpha");
		OrganizationalStructure foreign = structure(second, "Foreign");
		UserAccount actor = account("member", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, first);
		grant(actor, beta, Set.of(role));
		grant(actor, alpha, Set.of(role));
		MockHttpSession session = session(actor);

		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].structureID").value(alpha.getStructureID().toString()))
				.andExpect(jsonPath("$[0].structureName").value("Alpha"))
				.andExpect(jsonPath("$[0].length()").value(2))
				.andExpect(jsonPath("$[0].tenantID").doesNotExist())
				.andExpect(jsonPath("$[1].structureID").value(beta.getStructureID().toString()));

		mvc.perform(get("/api/structures/" + alpha.getStructureID()).session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$.structureName").value("Alpha"));
		assertNotFound(session, foreign.getStructureID());
	}

	@Test
	void returnsAnEmptyListForAnActiveAccountWithoutAnyStructure() throws Exception {
		UserAccount actor = account("without_access", Set.of(UserRole.EMPLOYEE));
		mvc.perform(get("/api/structures").session(session(actor))).andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void aMembershipWithoutRolesIsACandidateButDoesNotGrantVisibility() throws Exception {
		Tenant tenant = tenant("Fictitious empty roles");
		OrganizationalStructure structure = structure(tenant, "No access");
		UserAccount actor = account("empty_roles", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, tenant);
		grant(actor, structure, Set.of());
		assertEquals(List.of(structure.getStructureID()), ids(structureStore.findCandidatesByUser(actor.getUserID())));
		MockHttpSession session = session(actor);
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk()).andExpect(content().json("[]"));
		assertNotFound(session, structure.getStructureID());
	}

	@Test
	void aDesignatedSuperAdminSeesAllItsCompanyStructuresWithoutLocalMemberships() throws Exception {
		Tenant first = tenant("Fictitious owner A");
		Tenant second = tenant("Fictitious owner B");
		OrganizationalStructure alpha = structure(first, "Alpha");
		OrganizationalStructure beta = structure(first, "Beta");
		OrganizationalStructure foreign = structure(second, "Foreign");
		assertTrue(membershipStore.findByUser(first.owner().getUserID()).isEmpty());
		assertEquals(Set.of(alpha.getStructureID(), beta.getStructureID()),
				Set.copyOf(ids(structureStore.findCandidatesByUser(first.owner().getUserID()))));
		MockHttpSession session = session(first.owner());
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].structureName").value("Alpha"));
		mvc.perform(get("/api/structures/" + beta.getStructureID()).session(session)).andExpect(status().isOk());
		assertNotFound(session, foreign.getStructureID());
	}

	@Test
	void candidatesAreNotDuplicatedWhenDesignationAndLocalMembershipBothMatch() {
		Tenant tenant = tenant("Fictitious overlap");
		OrganizationalStructure structure = structure(tenant, "Only once");
		grant(tenant.owner(), structure, Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));
		assertEquals(List.of(structure.getStructureID()),
				ids(structureStore.findCandidatesByUser(tenant.owner().getUserID())));
	}

	@Test
	void aGlobalSuperAdminRoleWithoutDesignationDoesNotGrantAllStructures() throws Exception {
		Tenant tenant = tenant("Fictitious designation");
		OrganizationalStructure structure = structure(tenant, "Private");
		UserAccount impostor = account("not_designated", Set.of(UserRole.SUPER_ADMIN));
		joinTenant(impostor, tenant);
		MockHttpSession session = session(impostor);
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk()).andExpect(content().json("[]"));
		assertNotFound(session, structure.getStructureID());
	}

	@Test
	void aDesignatedSuperAdminCannotUseALocalRoleToEnterAnotherCompany() throws Exception {
		Tenant first = tenant("Fictitious isolated owner A");
		Tenant second = tenant("Fictitious isolated owner B");
		OrganizationalStructure own = structure(first, "Own");
		OrganizationalStructure foreign = structure(second, "Foreign");
		joinTenant(first.owner(), second);
		grant(first.owner(), foreign, Set.of(UserRole.ADMIN));
		MockHttpSession session = session(first.owner());
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].structureID").value(own.getStructureID().toString()));
		assertNotFound(session, foreign.getStructureID());
	}

	@Test
	void membershipRemovalIsReflectedOnTheNextReadWithoutReusingTheOldList() throws Exception {
		Tenant tenant = tenant("Fictitious withdrawal");
		OrganizationalStructure removed = structure(tenant, "Removed");
		OrganizationalStructure retained = structure(tenant, "Retained");
		UserAccount actor = account("withdrawal", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, tenant);
		grant(actor, removed, Set.of(UserRole.EMPLOYEE));
		grant(actor, retained, Set.of(UserRole.ACCOUNTANT));
		MockHttpSession session = session(actor);
		mvc.perform(get("/api/structures").session(session)).andExpect(jsonPath("$.length()").value(2));

		membershipStore.deleteByUserAndStructure(actor.getUserID(), removed.getStructureID());

		assertNotFound(session, removed.getStructureID());
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].structureID").value(retained.getStructureID().toString()));
	}

	@Test
	void removingAllLocalRolesAlsoRemovesVisibilityWhileTheMembershipRemains() throws Exception {
		Tenant tenant = tenant("Fictitious roles withdrawn");
		OrganizationalStructure structure = structure(tenant, "Removed roles");
		UserAccount actor = account("roles_withdrawn", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, tenant);
		grant(actor, structure, Set.of(UserRole.ADMIN));
		MockHttpSession session = session(actor);
		mvc.perform(get("/api/structures/" + structure.getStructureID()).session(session)).andExpect(status().isOk());
		grant(actor, structure, Set.of());
		assertFalse(membershipStore.findByUserAndStructure(actor.getUserID(), structure.getStructureID()).isEmpty());
		assertNotFound(session, structure.getStructureID());
	}

	@Test
	void removingTheTenantMembershipWithdrawsTheStructureAccessOnTheNextRequest() throws Exception {
		Tenant tenant = tenant("Fictitious tenant withdrawal");
		OrganizationalStructure structure = structure(tenant, "Withdrawn");
		UserAccount actor = account("tenant_withdrawal", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, tenant);
		grant(actor, structure, Set.of(UserRole.ADMIN));
		MockHttpSession session = session(actor);
		mvc.perform(get("/api/structures/" + structure.getStructureID()).session(session)).andExpect(status().isOk());
		membershipStore.deleteByUserAndStructure(actor.getUserID(), structure.getStructureID());
		jdbc.update("DELETE FROM tenant_memberships WHERE user_id = ? AND tenant_id = ?",
				actor.getUserID(), tenant.id());
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isOk()).andExpect(content().json("[]"));
		assertNotFound(session, structure.getStructureID());
	}

	@Test
	void aDisabledAccountCannotUseItsPreviouslyAuthenticatedSession() throws Exception {
		UserAccount actor = account("disabled_session", Set.of(UserRole.EMPLOYEE));
		MockHttpSession session = session(actor);
		jdbc.update("UPDATE user_accounts SET status = ? WHERE user_id = ?",
				UserAccountStatus.DISABLED.name(), actor.getUserID());
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isUnauthorized());
		assertTrue(session.isInvalid());
	}

	@Test
	void nonexistentAndForeignUuidsHaveTheSameStatusBodyAndCachePolicy() throws Exception {
		Tenant first = tenant("Fictitious oracle A");
		Tenant second = tenant("Fictitious oracle B");
		OrganizationalStructure accessible = structure(first, "Accessible");
		OrganizationalStructure foreign = structure(second, "Foreign");
		UserAccount actor = account("oracle", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, first);
		grant(actor, accessible, Set.of(UserRole.EMPLOYEE));
		MockHttpSession session = session(actor);
		String missing = assertNotFound(session, UUID.randomUUID());
		assertEquals(missing, assertNotFound(session, foreign.getStructureID()));
	}

	@Test
	void aChangedSessionVersionProduces401InsteadOfAnEmptyListOr404() throws Exception {
		Tenant tenant = tenant("Fictitious revoked session");
		OrganizationalStructure structure = structure(tenant, "Protected");
		UserAccount actor = account("revoked_session", Set.of(UserRole.EMPLOYEE));
		joinTenant(actor, tenant);
		grant(actor, structure, Set.of(UserRole.EMPLOYEE));
		MockHttpSession session = session(actor);
		jdbc.update("UPDATE user_accounts SET session_version = session_version + 1 WHERE user_id = ?",
				actor.getUserID());
		mvc.perform(get("/api/structures").session(session)).andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}"));
		assertTrue(session.isInvalid());
	}

	@Test
	void malformedIdsAreRejectedAndTheDefaultDenyPolicyRemainsInPlace() throws Exception {
		UserAccount actor = account("routing", Set.of(UserRole.EMPLOYEE));
		MockHttpSession session = session(actor);
		mvc.perform(get("/api/structures/not-a-uuid").session(session)).andExpect(status().isBadRequest())
				.andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}"));
		mvc.perform(get("/api/structures/" + UUID.randomUUID() + "/unexpected").session(session))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/structures").session(session).with(csrf())).andExpect(status().isForbidden());
	}

	private String assertNotFound(MockHttpSession session, UUID structureID) throws Exception {
		return mvc.perform(get("/api/structures/" + structureID).session(session))
				.andExpect(status().isNotFound()).andExpect(content().json("{\"code\":\"NOT_FOUND\"}"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andReturn().getResponse().getContentAsString();
	}

	private MockHttpSession session(UserAccount actor) {
		MockHttpSession session = new MockHttpSession();
		var security = SecurityContextHolder.createEmptyContext();
		security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
		lifetime.start(session, true);
		sessions.registerNewSession(session.getId(), actor);
		return session;
	}

	private UserAccount account(String label, Set<UserRole> globalRoles) {
		UUID id = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Fictitious", "Structure test", 35,
				"fictitious_" + id + "@example.invalid", "+12025550100", null);
		employees.saveAndFlush(new EmployeeEntity(employee));
		UserAccount actor = new UserAccount(id, "fictitious_" + label + "_" + id, UserAccountStatus.ACTIVE,
				globalRoles, employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
				employee.getEmail(), employee.getPhoneNumber());
		accounts.saveAndFlush(new UserAccountEntity(actor));
		return actor;
	}

	private Tenant tenant(String name) {
		UserAccount owner = account("super_admin", Set.of(UserRole.SUPER_ADMIN));
		Tenant tenant = new Tenant(UUID.randomUUID(), owner);
		tenants.saveAndFlush(
				new TenantOrganizationEntity(new TenantOrganization(tenant.id(), name, owner.getUserID())));
		joinTenant(owner, tenant);
		return tenant;
	}

	private void joinTenant(UserAccount actor, Tenant tenant) {
		jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", actor.getUserID(),
				tenant.id());
	}

	private OrganizationalStructure structure(Tenant tenant, String name) {
		OrganizationalStructure structure = new OrganizationalStructure(UUID.randomUUID(), name, tenant.id());
		structures.saveAndFlush(new OrganizationalStructureEntity(structure));
		return structure;
	}

	private void grant(UserAccount actor, OrganizationalStructure structure, Set<UserRole> roles) {
		memberships.saveAndFlush(new StructureMembershipEntity(
				new StructureMembership(actor.getUserID(), structure.getStructureID(), roles),
				structure.getTenantID()));
	}

	private List<UUID> ids(List<OrganizationalStructure> structures) {
		return structures.stream().map(OrganizationalStructure::getStructureID).toList();
	}

	private record Tenant(UUID id, UserAccount owner) {
	}
}
