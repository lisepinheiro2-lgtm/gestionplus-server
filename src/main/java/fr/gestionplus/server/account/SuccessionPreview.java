package fr.gestionplus.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetSource;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.UserRole;

public record SuccessionPreview(UUID tenantID, List<UUID> structures, Participant outgoing,
		Participant successor, List<UUID> adminRolesToTransfer, boolean outgoingWillBeDisabled)
		implements AuditTargetSource {

	public SuccessionPreview {
		Objects.requireNonNull(tenantID);
		Objects.requireNonNull(outgoing);
		Objects.requireNonNull(successor);
		structures = sortedIDs(structures);
		adminRolesToTransfer = sortedIDs(adminRolesToTransfer);
		if (outgoing.userID().equals(successor.userID()) || !structures.containsAll(adminRolesToTransfer)
				|| !structures.containsAll(outgoing.structureIDs()) || !structures.containsAll(successor.structureIDs())) {
			throw new IllegalArgumentException("Le périmètre du récapitulatif est incohérent.");
		}
	}

	@Override
	public Set<AuditTarget> auditTargets() {
		return Set.of(new AuditTarget(AuditTargetType.TENANT_ORGANIZATION, tenantID),
				new AuditTarget(AuditTargetType.ACCOUNT, outgoing.userID()),
				new AuditTarget(AuditTargetType.ACCOUNT, successor.userID()));
	}

	public String confirmationDigest() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonicalForm().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 indisponible.", exception);
		}
	}

	// Every token is a fixed UUID, an enum name, a count, or a boolean. No token contains a newline.
	private String canonicalForm() {
		StringBuilder canonical = new StringBuilder("gestionplus:super-admin-succession:1\n");
		canonical.append(tenantID).append('\n');
		appendIDs(canonical, structures);
		appendParticipant(canonical, outgoing);
		appendParticipant(canonical, successor);
		appendIDs(canonical, adminRolesToTransfer);
		return canonical.append(outgoingWillBeDisabled).append('\n').toString();
	}

	private static void appendIDs(StringBuilder canonical, List<UUID> ids) {
		canonical.append(ids.size()).append('\n');
		ids.forEach(id -> canonical.append(id).append('\n'));
	}

	private static void appendParticipant(StringBuilder canonical, Participant participant) {
		canonical.append(participant.userID()).append('\n');
		appendAssignments(canonical, participant.before());
		appendAssignments(canonical, participant.after());
	}

	private static void appendAssignments(StringBuilder canonical, Assignments assignments) {
		canonical.append(assignments.tenantMember()).append('\n').append(assignments.superAdmin()).append('\n')
				.append(assignments.memberships().size()).append('\n');
		for (LocalRoles membership : assignments.memberships()) {
			canonical.append(membership.structureID()).append('\n').append(membership.roles().size()).append('\n');
			membership.roles().forEach(role -> canonical.append(role.name()).append('\n'));
		}
	}

	static List<UUID> sortedIDs(List<UUID> ids) {
		List<UUID> sorted = ids.stream().sorted().toList();
		if (sorted.stream().distinct().count() != sorted.size()) {
			throw new IllegalArgumentException("Les identifiants doivent être uniques.");
		}
		return sorted;
	}

	public record Participant(UUID userID, Assignments before, Assignments after) {
		public Participant {
			Objects.requireNonNull(userID);
			Objects.requireNonNull(before);
			Objects.requireNonNull(after);
		}

		private List<UUID> structureIDs() {
			return java.util.stream.Stream.concat(before.memberships().stream(), after.memberships().stream())
					.map(LocalRoles::structureID).distinct().toList();
		}
	}

	public record Assignments(boolean tenantMember, boolean superAdmin, List<LocalRoles> memberships) {
		public Assignments {
			memberships = memberships.stream().sorted(Comparator.comparing(LocalRoles::structureID)).toList();
			sortedIDs(memberships.stream().map(LocalRoles::structureID).toList());
			if (superAdmin && !tenantMember) {
				throw new IllegalArgumentException("Le SUPER_ADMIN doit être rattaché à l’organisation.");
			}
		}

		public Set<UserRole> rolesIn(UUID structureID) {
			return memberships.stream().filter(membership -> membership.structureID().equals(structureID))
					.findFirst().map(membership -> Set.copyOf(membership.roles())).orElse(Set.of());
		}
	}

	public record LocalRoles(UUID structureID, List<UserRole> roles) {
		public LocalRoles {
			Objects.requireNonNull(structureID);
			roles = roles.stream().distinct().sorted(Comparator.comparing(UserRole::name)).toList();
			if (roles.contains(UserRole.SUPER_ADMIN)) {
				throw new IllegalArgumentException("Le rôle SUPER_ADMIN est organisationnel.");
			}
		}
	}
}
