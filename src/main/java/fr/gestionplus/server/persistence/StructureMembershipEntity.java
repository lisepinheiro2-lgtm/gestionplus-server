package fr.gestionplus.server.persistence;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "structure_memberships")
public class StructureMembershipEntity {

	@EmbeddedId
	private StructureMembershipId id;

	@ElementCollection
	@CollectionTable(name = "structure_membership_roles", joinColumns = {
			@JoinColumn(name = "user_id", referencedColumnName = "user_id"),
			@JoinColumn(name = "structure_id", referencedColumnName = "structure_id") })
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private Set<UserRole> roles = new HashSet<>();

	protected StructureMembershipEntity() {
	}

	public StructureMembershipEntity(StructureMembership membership) {

		Objects.requireNonNull(membership);
		UUID userID = membership.getUserID();
		UUID structureID = membership.getStructureID();

		this.id = new StructureMembershipId(userID, structureID);
		this.roles = new HashSet<>(membership.getRoles());
	}

	public StructureMembership toDomain() {
		return new StructureMembership(id.getUserID(), id.getStructureID(), roles);
	}
}
