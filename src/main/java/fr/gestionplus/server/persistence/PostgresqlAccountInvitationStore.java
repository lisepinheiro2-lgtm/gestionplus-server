package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import fr.gestionplus.server.account.AccountInvitationStore;

@Repository
public class PostgresqlAccountInvitationStore implements AccountInvitationStore {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlAccountInvitationStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<Invitation> findByAccount(UUID userID) {
		return jdbcTemplate.query("SELECT * FROM account_invitations WHERE user_id = ?", this::read, userID)
				.stream().findFirst();
	}

	@Override
	public Optional<Invitation> findByID(UUID invitationID) {
		return jdbcTemplate.query("SELECT * FROM account_invitations WHERE invitation_id = ?", this::read, invitationID)
				.stream().findFirst();
	}

	@Override
	public List<UUID> findExpired(Instant now) {
		return jdbcTemplate.queryForList("""
				SELECT invitation_id FROM account_invitations
				WHERE expires_at <= ?
				ORDER BY expires_at, invitation_id
				LIMIT 100
				""", UUID.class, atUtc(now));
	}

	@Override
	public void save(Invitation invitation) {
		jdbcTemplate.update("""
				INSERT INTO account_invitations
				    (user_id, invitation_id, structure_id, token_digest, expires_at, completed_at, reintegration)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (user_id) DO UPDATE SET
				    invitation_id = EXCLUDED.invitation_id,
				    structure_id = EXCLUDED.structure_id,
				    token_digest = EXCLUDED.token_digest,
				    expires_at = EXCLUDED.expires_at,
				    completed_at = EXCLUDED.completed_at,
				    reintegration = EXCLUDED.reintegration
				""", invitation.userID(), invitation.invitationID(), invitation.structureID(), invitation.tokenDigest(),
				atUtc(invitation.expiresAt()), atUtc(invitation.completedAt()), invitation.reintegration());
	}

	@Override
	public void removeForAccount(UUID userID) {
		jdbcTemplate.update("DELETE FROM account_invitations WHERE user_id = ?", userID);
	}

	private Invitation read(ResultSet row, int rowNumber) throws SQLException {
		return new Invitation(row.getObject("invitation_id", UUID.class), row.getObject("user_id", UUID.class),
				row.getObject("structure_id", UUID.class), row.getString("token_digest"),
				readInstant(row, "expires_at"), readInstant(row, "completed_at"), row.getBoolean("reintegration"));
	}

	private Instant readInstant(ResultSet row, String column) throws SQLException {
		OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime atUtc(Instant value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}
}
