package fr.gestionplus.server.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class StructureMembershipId implements Serializable {

	@Column(name = "user_id")
	private UUID userID;

	@Column(name = "structure_id")
	private UUID structureID;

	public StructureMembershipId() {
	}

	public StructureMembershipId(UUID userID, UUID structureID) {
		this.userID = Objects.requireNonNull(userID);
		this.structureID = Objects.requireNonNull(structureID);
	}

	public boolean equals(Object obj) {

		if (!(obj instanceof StructureMembershipId))
			return false;

		StructureMembershipId other = (StructureMembershipId) obj;

		return this.structureID.equals(other.getStructureID()) && this.userID.equals(other.getUserID());
	}

	@Override
	public int hashCode() {
		return Objects.hash(userID, structureID);
	}

	public UUID getUserID() {
		return userID;
	}

	public UUID getStructureID() {
		return structureID;
	}
}
