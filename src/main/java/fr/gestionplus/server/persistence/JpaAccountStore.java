package fr.gestionplus.server.persistence;

import java.util.Objects;

import org.springframework.stereotype.Repository;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.UserAccount;

@Repository
public class JpaAccountStore implements AccountStore {

	private final UserAccountJpaRepository accountJpaRepository;
	private final EmployeeJpaRepository employeeJpaRepository;

	public JpaAccountStore(UserAccountJpaRepository accountJpaRepository, EmployeeJpaRepository employeeJpaRepository) {
		this.accountJpaRepository = Objects.requireNonNull(accountJpaRepository);
		this.employeeJpaRepository = Objects.requireNonNull(employeeJpaRepository);
	}

	@Override
	public void createAccount(UserAccount account) {
		Objects.requireNonNull(account);

		if (account.getEmployeeID() == null)
			throw new IllegalArgumentException("Un nouveau compte doit être rattaché à un salarié.");

		if (!employeeJpaRepository.existsById(account.getEmployeeID()))
			throw new IllegalArgumentException("Le salarié associé au compte n'existe pas.");

		UserAccountEntity accountEntity = new UserAccountEntity(account);
		accountJpaRepository.save(accountEntity);
	}
}
