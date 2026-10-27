package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.UUID;

import fr.gestionplus.server.organization.OrganizationalUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizational_units")
public class OrganizationalUnitEntity {

	@Id
	@Column(name = "unit_id", nullable = false)
	private UUID unitId;

	@Column(name = "unit_name", nullable = false)
	private String name;

	protected OrganizationalUnitEntity() {
	}

	public OrganizationalUnitEntity(OrganizationalUnit unit) {

		Objects.requireNonNull(unit);
		this.unitId = unit.getUnitId();
		this.name = unit.getName();
	}

	public OrganizationalUnit toDomain() {
		return new OrganizationalUnit(unitId, name);
	}

}
