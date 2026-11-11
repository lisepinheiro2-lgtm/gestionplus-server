package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import fr.gestionplus.server.account.AccountInvitationDeliveryStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.auth.UserRole;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PostgresqlAccountInvitationDeliveryStore implements AccountInvitationDeliveryStore {

	private final JdbcTemplate jdbc;
	private final JsonMapper json = JsonMapper.builder().build();

	public PostgresqlAccountInvitationDeliveryStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void enqueue(Request request) {
		String roles = json.writeValueAsString(request.roles().entrySet().stream()
				.map(entry -> new PreparedAccess(entry.getKey(), entry.getValue())).toList());
		jdbc.update("""
				INSERT INTO account_invitation_deliveries
				    (request_id, invitation_id, previous_invitation_id, user_id, structure_id, actor_id,
				     actor_version, target_version, email, action, reintegration, token_digest, prepared_roles)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
				""", request.requestID(), request.invitationID(), request.previousInvitationID(), request.userID(),
				request.structureID(), request.actorID(), request.actorVersion(), request.targetVersion(),
				request.email(), request.action().name(), request.reintegration(), request.tokenDigest(), roles);
	}

	@Override
	public Optional<Request> findPending(UUID userID) {
		return jdbc.query("""
				SELECT * FROM account_invitation_deliveries WHERE user_id = ? AND state = 'PENDING'
				""", this::read, userID).stream().findFirst();
	}

	@Override
	public Optional<Request> findById(UUID requestID) {
		return jdbc.query("""
				SELECT * FROM account_invitation_deliveries WHERE request_id = ? AND state = 'PENDING'
				""", this::read, requestID).stream().findFirst();
	}

	@Override
	public Optional<Request> findByInvitation(UUID invitationID) {
		return jdbc.query("""
				SELECT * FROM account_invitation_deliveries WHERE invitation_id = ? AND state = 'PENDING'
				""", this::read, invitationID).stream().findFirst();
	}

	@Override
	public Optional<Request> lockById(UUID requestID) {
		return jdbc.query("""
				SELECT * FROM account_invitation_deliveries
				WHERE request_id = ? AND state = 'PENDING' FOR UPDATE SKIP LOCKED
				""", this::read, requestID).stream().findFirst();
	}

	@Override
	public List<UUID> findDue() {
		return jdbc.queryForList("""
				SELECT request_id FROM account_invitation_deliveries
				WHERE state = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
				ORDER BY next_attempt_at, request_id LIMIT 100
				""", UUID.class);
	}

	@Override
	public boolean wasSent(UUID requestID) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM account_invitation_deliveries WHERE request_id = ? AND state = 'SENT'
				)
				""", Boolean.class, requestID));
	}

	@Override
	public void complete(UUID requestID) {
		jdbc.update("""
				UPDATE account_invitation_deliveries SET state = 'SENT', sent_at = CURRENT_TIMESTAMP
				WHERE request_id = ? AND state = 'PENDING'
				""", requestID);
	}

	@Override
	public void retry(UUID requestID) {
		jdbc.update("""
				UPDATE account_invitation_deliveries
				SET attempts = attempts + 1, next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
				WHERE request_id = ? AND state = 'PENDING'
				""", requestID);
	}

	@Override
	public void cancel(UUID requestID) {
		jdbc.update("""
				UPDATE account_invitation_deliveries SET state = 'CANCELLED'
				WHERE request_id = ? AND state = 'PENDING'
				""", requestID);
	}

	private Request read(ResultSet row, int rowNumber) throws SQLException {
		PreparedAccess[] accesses = json.readValue(row.getString("prepared_roles"), PreparedAccess[].class);
		Map<UUID, Set<UserRole>> roles = Arrays.stream(accesses)
				.collect(Collectors.toMap(PreparedAccess::structureID, PreparedAccess::roles));
		return new Request(row.getObject("request_id", UUID.class), row.getObject("invitation_id", UUID.class),
				row.getObject("previous_invitation_id", UUID.class), row.getObject("user_id", UUID.class),
				row.getObject("structure_id", UUID.class), row.getObject("actor_id", UUID.class),
				row.getLong("actor_version"), row.getLong("target_version"), row.getString("email"),
				AuditAction.valueOf(row.getString("action")), row.getBoolean("reintegration"),
				row.getString("token_digest"), roles);
	}

	private record PreparedAccess(UUID structureID, Set<UserRole> roles) {
	}
}
