package fr.gestionplus.server.audit;

import java.util.Set;

import fr.gestionplus.server.auth.UserRole;

public record AuditMetadata(Set<UserRole> grantedRoles, Set<UserRole> revokedRoles, AuditReason reason) {

	private static final AuditMetadata EMPTY = new AuditMetadata(Set.of(), Set.of());

	public AuditMetadata(Set<UserRole> grantedRoles, Set<UserRole> revokedRoles) {
		this(grantedRoles, revokedRoles, null);
	}

	public AuditMetadata {
		grantedRoles = Set.copyOf(grantedRoles);
		revokedRoles = Set.copyOf(revokedRoles);
	}

	public static AuditMetadata empty() {
		return EMPTY;
	}

	public static AuditMetadata because(AuditReason reason) {
		return new AuditMetadata(Set.of(), Set.of(), reason);
	}

	public static AuditMetadata rolesGranted(Set<UserRole> roles) {
		return new AuditMetadata(roles, Set.of());
	}

	public static AuditMetadata rolesRevoked(Set<UserRole> roles) {
		return new AuditMetadata(Set.of(), roles);
	}
}
