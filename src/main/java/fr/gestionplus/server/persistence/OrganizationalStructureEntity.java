package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.UUID;

import fr.gestionplus.server.organization.OrganizationalStructure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizational_structures")
public class OrganizationalStructureEntity {

	@Id
	@Column(name = "structure_id", nullable = false)
	private UUID structureID;

	@Column(name = "structure_name", nullable = false)
	private String structureName;

	protected OrganizationalStructureEntity() {
	}

	public OrganizationalStructureEntity(OrganizationalStructure structure) {

		Objects.requireNonNull(structure);
		this.structureID = structure.getStructureID();
		this.structureName = structure.getStructureName();
	}

	public OrganizationalStructure toDomain() {
		return new OrganizationalStructure(structureID, structureName);
	}
}
