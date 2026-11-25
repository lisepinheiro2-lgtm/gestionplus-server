package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionPreview;
import fr.gestionplus.server.account.SuccessionRevision;
import fr.gestionplus.server.account.SuccessionRevisionStore;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PostgresqlSuccessionRevisionStore implements SuccessionRevisionStore {

	private final JdbcTemplate jdbc;
	private final JsonMapper json = JsonMapper.builder().build();

	public PostgresqlSuccessionRevisionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void create(SuccessionRevision revision) {
		jdbc.update("""
				INSERT INTO succession_revisions (offer_id, revision, preview, preview_digest, created_at)
				VALUES (?, ?, CAST(? AS jsonb), ?, ?)
				""", revision.offerID(), revision.revision(), json.writeValueAsString(revision.preview()),
				revision.previewDigest(), revision.createdAt().atOffset(ZoneOffset.UTC));
	}

	@Override
	public Optional<SuccessionRevision> find(UUID offerID, long revision) {
		return jdbc.query("SELECT * FROM succession_revisions WHERE offer_id = ? AND revision = ?",
				this::read, offerID, revision).stream().findFirst();
	}

	@Override
	public List<SuccessionRevision> findHistory(UUID offerID) {
		return List.copyOf(jdbc.query("SELECT * FROM succession_revisions WHERE offer_id = ? ORDER BY revision",
				this::read, offerID));
	}

	private SuccessionRevision read(ResultSet row, int rowNumber) throws SQLException {
		if (row.getInt("preview_format") != 1) {
			throw new SQLException("Format de récapitulatif de succession inconnu.");
		}
		return new SuccessionRevision(row.getObject("offer_id", UUID.class), row.getLong("revision"),
				json.readValue(row.getString("preview"), SuccessionPreview.class), row.getString("preview_digest"),
				row.getObject("created_at", OffsetDateTime.class).toInstant());
	}
}
