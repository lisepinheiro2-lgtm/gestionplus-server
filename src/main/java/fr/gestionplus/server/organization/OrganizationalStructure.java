package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class OrganizationalStructure {

	private final UUID structureID;
	private final String structureName;
	
	public OrganizationalStructure(UUID structureID, String structureName) {
		this.structureID = Objects.requireNonNull(structureID);
		this.structureName = Objects.requireNonNull(structureName).strip();
		
		if (structureName.isBlank())
			throw new IllegalArgumentException("Le nom de la structure ne peut pas être vide");		
	}

	public UUID getStructureID() {
		return structureID;
	}

	public String getStructureName() {
		return structureName;
	}
}
