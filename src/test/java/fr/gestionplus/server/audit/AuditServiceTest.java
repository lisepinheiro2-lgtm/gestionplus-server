package fr.gestionplus.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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
				UUID.randomUUID());
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

	private AuditEvent savedEvent() {
		ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(store).save(eventCaptor.capture());
		return eventCaptor.getValue();
	}
}
