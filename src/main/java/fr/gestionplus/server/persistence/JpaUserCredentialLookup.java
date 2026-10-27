package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserCredentialLookup;

@Component
public class JpaUserCredentialLookup implements UserCredentialLookup {

	private final UserCredentialJpaRepository repository;

	public JpaUserCredentialLookup(UserCredentialJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository, "Le repository ne peut pas être null.");
	}

	@Override
	public Optional<UserCredential> findByUserId(UUID userId) {
		return repository.findById(userId).map(entity -> entity.toDomain());
	}
}
