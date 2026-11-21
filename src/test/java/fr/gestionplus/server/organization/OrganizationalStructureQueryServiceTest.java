package fr.gestionplus.server.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

class OrganizationalStructureQueryServiceTest {

	private final OrganizationalStructureStore store = mock(OrganizationalStructureStore.class);
	private final AuthorizationService authorization = mock(AuthorizationService.class);
	private final OrganizationalStructureQueryService service = new OrganizationalStructureQueryService(store, authorization);
	private UserAccount principal;

	@BeforeEach
	void createPrincipal() {
		principal = new UserAccount(UUID.randomUUID(), "fictitious_structure_user", UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), UUID.randomUUID(), "Fictitious", "User",
				"fictitious@example.invalid", "+12025550100");
	}

	@Test
	void filtersTheBatchThroughCurrentAuthorizationAndSortsByNameThenId() {
		UUID tenantID = UUID.randomUUID();
		OrganizationalStructure beta = structure("Beta", tenantID, 3);
		OrganizationalStructure firstAlpha = structure("Alpha", tenantID, 1);
		OrganizationalStructure secondAlpha = structure("Alpha", tenantID, 2);
		OrganizationalStructure forbidden = structure("Forbidden", tenantID, 4);
		when(store.findCandidatesByUser(principal.getUserID()))
				.thenReturn(List.of(beta, secondAlpha, forbidden, firstAlpha));
		for (OrganizationalStructure allowed : List.of(beta, firstAlpha, secondAlpha)) {
			when(authorization.rolesInStructureForSession(principal, allowed.getStructureID()))
					.thenReturn(Set.of(UserRole.EMPLOYEE));
		}
		when(authorization.rolesInStructureForSession(principal, forbidden.getStructureID())).thenReturn(Set.of());

		assertEquals(List.of(firstAlpha, secondAlpha, beta), service.listAccessibleStructures(principal));
		verify(store).findCandidatesByUser(principal.getUserID());
		for (OrganizationalStructure candidate : List.of(beta, firstAlpha, secondAlpha, forbidden)) {
			verify(store, never()).findById(candidate.getStructureID());
		}
	}

	@Test
	void anEmptyCandidateListDoesNotBecomeAnAuthorizationError() {
		when(store.findCandidatesByUser(principal.getUserID())).thenReturn(List.of());
		assertTrue(service.listAccessibleStructures(principal).isEmpty());
	}

	@Test
	void aStorageFailureIsNotConvertedIntoAnEmptyList() {
		DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Panne fictive.");
		when(store.findCandidatesByUser(principal.getUserID())).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> service.listAccessibleStructures(principal)));
	}

	@Test
	void aForbiddenDetailDoesNotReadItsDisplayData() {
		UUID structureID = UUID.randomUUID();
		when(authorization.rolesInStructureForSession(principal, structureID)).thenReturn(Set.of());
		assertTrue(service.getAccessibleStructure(principal, structureID).isEmpty());
		verify(store, never()).findById(structureID);
	}

	@Test
	void anAuthorizedDetailUsesTheStoredStructure() {
		OrganizationalStructure structure = structure("Fictitious", UUID.randomUUID(), 1);
		when(authorization.rolesInStructureForSession(principal, structure.getStructureID()))
				.thenReturn(Set.of(UserRole.EMPLOYEE));
		when(store.findById(structure.getStructureID())).thenReturn(Optional.of(structure));
		assertSame(structure, service.getAccessibleStructure(principal, structure.getStructureID()).orElseThrow());
	}

	private OrganizationalStructure structure(String name, UUID tenantID, long value) {
		return new OrganizationalStructure(new UUID(0, value), name, tenantID);
	}
}

