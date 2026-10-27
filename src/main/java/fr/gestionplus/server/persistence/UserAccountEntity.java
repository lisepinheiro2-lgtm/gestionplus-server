package fr.gestionplus.server.persistence;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_accounts")
public class UserAccountEntity {

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "employee_id")
	private UUID employeeID;

	@Column(name = "user_name", unique = true)
	private String userName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private UserAccountStatus statu;

	@ElementCollection
	@CollectionTable(name = "user_account_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private Set<UserRole> roles = new HashSet<>();

	protected UserAccountEntity() {
	}

	public UserAccountEntity(UserAccount account) {

		if (account == null)
			throw new NullPointerException("Le compte est null.");

		userId = account.getUserID();
		employeeID = account.getEmployeeID();
		userName = account.getUserName();
		statu = account.getStatu();
		roles = new HashSet<>(account.getRoles());
	}

	@PrePersist
	protected void requireEmployeeForCreation() {
		if (employeeID == null)
			throw new IllegalStateException("Un nouveau compte doit être rattaché à un salarié.");
	}

	public UserAccount toDomain() {
		return new UserAccount(userId, userName, statu, roles, employeeID);
	}
}