package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.UUID;

import fr.gestionplus.server.auth.UserCredential;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_credentials")
public class UserCredentialEntity {

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "password_hash", nullable = false, length = 512)
	private String passwordHash;

	protected UserCredentialEntity() {
	}

	public UserCredentialEntity(UserCredential credential) {

		Objects.requireNonNull(credential, "Le credential ne peut pas être null.");

		userId = credential.getUserId();
		passwordHash = credential.getPasswordHash();
	}

	public UserCredential toDomain() {
		return new UserCredential(userId, passwordHash);
	}
}
