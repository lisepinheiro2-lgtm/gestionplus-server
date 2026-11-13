package fr.gestionplus.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

class AuditServiceTest {

	private final AuditStore store = mock(AuditStore.class);
	private final AuditService service = new AuditService(store, new SpringAuditContextProvider());

	@AfterEach
	void clearsServerContext() {
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void recordsServerActorIpAndTimeWithImmutableRoleAndTargetSnapshots() {

		UUID actorId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();
		UserAccount actor = new UserAccount(actorId, "audit_test", UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN),
				UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", "0600000000");
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("192.0.2.10");
		request.addHeader("X-Forwarded-For", "203.0.113.10");
		request.addHeader("X-Actor-Id", targetId.toString());
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		AuditTarget target = new AuditTarget(AuditTargetType.ACCOUNT, targetId);
		Set<AuditTarget> targets = new HashSet<>(Set.of(target));
		Set<UserRole> roles = new HashSet<>(Set.of(UserRole.TEAM_LEAD, UserRole.EMPLOYEE));

		Instant before = Instant.now();
		service.record(AuditAction.STRUCTURE_ACCESS_GRANT, AuditResult.SUCCESS, targets,
				AuditMetadata.rolesGranted(roles));
		Instant after = Instant.now();

		roles.clear();
		targets.clear();

		AuditEvent event = savedEvent();
		assertEquals(actorId, event.getActorUserId());
		assertNotEquals(targetId, event.getActorUserId());
		assertEquals("192.0.2.10", event.getIpAddress());
		assertFalse(event.getOccurredAt().isBefore(before));
		assertFalse(event.getOccurredAt().isAfter(after));
		assertEquals(AuditAction.STRUCTURE_ACCESS_GRANT, event.getAction());
		assertEquals(AuditResult.SUCCESS, event.getResult());
		assertEquals(Set.of(target), event.getTargets());
		assertEquals(Set.of(UserRole.TEAM_LEAD, UserRole.EMPLOYEE), event.getMetadata().grantedRoles());
		assertTrue(event.getMetadata().revokedRoles().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> event.getTargets().clear());
		assertThrows(UnsupportedOperationException.class, () -> event.getMetadata().grantedRoles().clear());
	}

	@Test
	void rejectsNullMetadataAndNullRoles() {

		Set<UserRole> invalidRoles = new HashSet<>();
		invalidRoles.add(null);

		assertThrows(NullPointerException.class, () -> AuditMetadata.rolesGranted(null));
		assertThrows(NullPointerException.class, () -> AuditMetadata.rolesRevoked(invalidRoles));
		assertThrows(NullPointerException.class, () -> new AuditEvent(Instant.now(), UUID.randomUUID(), null,
				AuditAction.STRUCTURE_ACCESS_GRANT, AuditResult.SUCCESS, null, Set.of(), null));
	}

	@Test
	void keepsTheLegacyRecordOverload() {

		service.record(AuditAction.AUTH_LOGIN, AuditResult.FAILURE, Set.of());

		AuditEvent event = savedEvent();
		assertEquals(AuditAction.AUTH_LOGIN, event.getAction());
		assertEquals(AuditResult.FAILURE, event.getResult());
		assertEquals(AuditMetadata.empty(), event.getMetadata());
	}
	
    @Test
    void resolvesTheTenantFromTheStructureMembershipsOfTheAccountTarget() {

        UUID accountID = UUID.randomUUID();
        UUID structureID = UUID.randomUUID();
        UUID tenantID = UUID.randomUUID();

        when(memberships.findByUser(accountID))
                .thenReturn(List.of(new StructureMembership(accountID, structureID, Set.of(UserRole.EMPLOYEE))));
        when(structures.findById(structureID))
                .thenReturn(Optional.of(new OrganizationalStructure(structureID, "Structure A", tenantID)));

        resolvingService().record(AuditAction.ACCOUNT_PASSWORD_CHANGE, AuditResult.SUCCESS,
                Set.of(new AuditTarget(AuditTargetType.ACCOUNT, accountID)));

        assertEquals(tenantID, savedEvent().getTenantId());
    }

    @Test
    void resolvesTheTenantFromASuperAdminAccountWithoutStructureMembership() {

        UUID accountID = UUID.randomUUID();
        UUID tenantID = UUID.randomUUID();

        when(tenants.findBySuperAdminUserID(accountID))
                .thenReturn(Optional.of(new TenantOrganization(tenantID, "Entreprise A", accountID)));
        when(memberships.findByUser(accountID)).thenReturn(List.of());

        resolvingService().record(AuditAction.AUTH_SESSION_REVOKE, AuditResult.SUCCESS,
                Set.of(new AuditTarget(AuditTargetType.ACCOUNT, accountID)));

        assertEquals(tenantID, savedEvent().getTenantId());
    }

    @Test
    void keepsAnAccountEventGlobalWhenNoTenantCanBeResolved() {

        UUID accountID = UUID.randomUUID();

        when(tenants.findBySuperAdminUserID(accountID)).thenReturn(Optional.empty());
        when(memberships.findByUser(accountID)).thenReturn(List.of());

        resolvingService().record(AuditAction.AUTH_LOGIN, AuditResult.FAILURE,
                Set.of(new AuditTarget(AuditTargetType.ACCOUNT, accountID)));

        assertNull(savedEvent().getTenantId());
    }

    @Test
    void keepsAnAccountEventGlobalWhenItSpansSeveralTenants() {

        UUID accountID = UUID.randomUUID();
        UUID firstStructureID = UUID.randomUUID();
        UUID secondStructureID = UUID.randomUUID();

        when(memberships.findByUser(accountID)).thenReturn(List.of(
                new StructureMembership(accountID, firstStructureID, Set.of(UserRole.EMPLOYEE)),
                new StructureMembership(accountID, secondStructureID, Set.of(UserRole.EMPLOYEE))));
        when(structures.findById(firstStructureID)).thenReturn(
                Optional.of(new OrganizationalStructure(firstStructureID, "Structure A", UUID.randomUUID())));
        when(structures.findById(secondStructureID)).thenReturn(
                Optional.of(new OrganizationalStructure(secondStructureID, "Structure B", UUID.randomUUID())));

        resolvingService().record(AuditAction.ACCOUNT_PASSWORD_CHANGE, AuditResult.SUCCESS,
                Set.of(new AuditTarget(AuditTargetType.ACCOUNT, accountID)));

        assertNull(savedEvent().getTenantId());
    }

	private AuditEvent savedEvent() {
		ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(store).save(eventCaptor.capture());
		return eventCaptor.getValue();
	}
	
    private final OrganizationalStructureStore structures = mock(OrganizationalStructureStore.class);
    private final TenantOrganizationStore tenants = mock(TenantOrganizationStore.class);
    private final StructureMembershipStore memberships = mock(StructureMembershipStore.class);

    private AuditService resolvingService() {
        return new AuditService(store::save, new SpringAuditContextProvider(), structures, tenants, memberships);
    }
}
