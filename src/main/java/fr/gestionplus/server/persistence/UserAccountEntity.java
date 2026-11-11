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

	@Column(name = "session_version", nullable = false)
	private long sessionVersion;

	@Column(name = "user_name", unique = true)
	private String userName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private UserAccountStatus statu;

	@Column(name = "user_last_name", nullable = false)
	private String lastName;

	@Column(name = "user_first_name", nullable = false)
	private String firstName;

	@Column(name = "user_email", nullable = false)
	private String email;

	@Column(name = "user_phone_number", nullable = false)
	private String phoneNumber;

	@ElementCollection
	@CollectionTable(name = "user_account_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private Set<UserRole> roles = new HashSet<>();

	protected UserAccountEntity() {
	}

	public UserAccountEntity(UserAccount account) {

		if (account == null) {
			throw new NullPointerException("Le compte est null.");
		}

		userId = account.getUserID();
		employeeID = account.getEmployeeID();
		sessionVersion = account.getSessionVersion();
		userName = account.getUserName();
		statu = account.getStatu();
		roles = new HashSet<>(account.getRoles());
		this.lastName = account.getLastName();
		this.firstName = account.getFirstName();
		this.email = account.getEmail();
		this.phoneNumber = account.getPhoneNumber();
	}

	@PrePersist
	protected void validateCreation() {

		if (statu == UserAccountStatus.PENDING_ACTIVATION) {

			if (!roles.isEmpty()) {
				throw new IllegalStateException("Un compte en attente doit être créé sans rôle global.");
			}

			return;
		}

		if (employeeID == null) {
			throw new IllegalStateException("Un nouveau compte doit être rattaché à un salarié.");
		}

	}

	public UserAccount toDomain() {
		return new UserAccount(userId, userName, statu, roles, employeeID, lastName, firstName, email, phoneNumber,
				sessionVersion);
	}

	void setPendingActivation() {

		if (statu != UserAccountStatus.DISABLED) {
			throw new IllegalStateException("Le compte doit être désactivé.");
		}

		sessionVersion++;
		statu = UserAccountStatus.PENDING_ACTIVATION;
	}

	void updateEmail(String email) {

		if (email == null || email.isBlank() || email.length() > 255) {
			throw new IllegalArgumentException("L’adresse e-mail est invalide.");
		}

		this.email = email;
	}

	void activate(String userName) {

		if (statu != UserAccountStatus.PENDING_ACTIVATION || employeeID == null) {
			throw new IllegalStateException("Le compte doit être en attente et associé à un salarié.");
		}

		if (userName == null || userName.isBlank() || userName.strip().length() > 255) {
			throw new IllegalArgumentException("Le nom d’utilisateur est invalide.");
		}

		this.userName = userName.strip();
		statu = UserAccountStatus.ACTIVE;
	}

	void advanceSessionVersion() {
		sessionVersion++;
	}

	void disable() {

		if (statu != UserAccountStatus.DISABLED) {
			sessionVersion++;
		}

		statu = UserAccountStatus.DISABLED;
		roles.clear();
	}
}
