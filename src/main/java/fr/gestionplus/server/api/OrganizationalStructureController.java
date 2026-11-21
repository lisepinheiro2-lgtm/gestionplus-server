package fr.gestionplus.server.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureQueryService;

@RestController
public class OrganizationalStructureController {

	private final OrganizationalStructureQueryService queryService;

	public OrganizationalStructureController(OrganizationalStructureQueryService queryService) {
		this.queryService = Objects.requireNonNull(queryService);
	}

	@GetMapping(OrganizationalStructureRoutes.STRUCTURES_PATH)
	public List<StructureSummary> listStructures(Authentication authentication) {

		if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount)
				|| !authentication.isAuthenticated()) {
			throw new AuthenticationCredentialsNotFoundException("Crédential introuvable.");
		}

		UserAccount account = (UserAccount) authentication.getPrincipal();
		List<OrganizationalStructure> structures = queryService.listAccessibleStructures(account);

		return structures.stream().map(s -> new StructureSummary(s.getStructureID(), s.getStructureName())).toList();
	}

	@GetMapping(OrganizationalStructureRoutes.STRUCTURE_PATH)
	public StructureSummary findStructure(Authentication authentication, @PathVariable UUID structureID) {

		if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount)
				|| !authentication.isAuthenticated()) {
			throw new AuthenticationCredentialsNotFoundException("Crédential introuvable.");
		}

		UserAccount account = (UserAccount) authentication.getPrincipal();
		Optional<OrganizationalStructure> structure = queryService.getAccessibleStructure(account, structureID);

		return structure
				.map(s -> new StructureSummary(s.getStructureID(), s.getStructureName()))
				.orElseThrow(() -> new AuditedAccessDeniedException("Aucune structure trouvée.",
						AuditReason.OUTSIDE_STRUCTURE));
	}

}
