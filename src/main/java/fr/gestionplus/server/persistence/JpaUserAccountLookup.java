package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountLookup;

@Component
public class JpaUserAccountLookup implements UserAccountLookup {

	private final UserAccountJpaRepository repository;

	public JpaUserAccountLookup(UserAccountJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository, "Le repository ne peut pas être null.");
	}

	@Override
	public Optional<UserAccount> findByUserName(String userName) {
		return repository.findByUserName(userName).map(entity -> entity.toDomain());
	}
}
