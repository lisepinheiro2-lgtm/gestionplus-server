package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class UserAccount {

	private final UUID userID;
	private final UUID employeeID;
	private final long sessionVersion;
	private String userName;
	private UserAccountStatus statu;
	private final Set<UserRole> roles;
	private final String lastName;
	private final String firstName;
	private String email;
	private String phoneNumber;

	public UserAccount(UUID userID, String userName, UserAccountStatus statu, Set<UserRole> roles, UUID employeeID,
			String lastName, String firstName, String email, String phoneNumber) {
		this(userID, userName, statu, roles, employeeID, lastName, firstName, email, phoneNumber, 0);
	}

	public UserAccount(UUID userID, String userName, UserAccountStatus statu, Set<UserRole> roles, UUID employeeID,
			String lastName, String firstName, String email, String phoneNumber, long sessionVersion) {
		this.sessionVersion = sessionVersion;
		this.userID = Objects.requireNonNull(userID, "L'UUID de l'utilisateur ne peut pas être null");
		this.employeeID = employeeID;
		this.lastName = requireIdentityValue(lastName, "Le nom");
		this.firstName = requireIdentityValue(firstName, "Le prénom");
		this.email = requireIdentityValue(email, "L’adresse e-mail");
		this.phoneNumber = requireIdentityValue(phoneNumber, "Le numéro de téléphone");

		if (userName != null) {
			this.userName = userName.strip();
		}

		this.statu = Objects.requireNonNull(statu);

		if (userName == null && statu == UserAccountStatus.ACTIVE) {
			throw new IllegalArgumentException("Le nom d'utilisateur ne peut pas être null");
		}

		if (userName != null && this.userName.isBlank()) {
			throw new IllegalArgumentException("Le nom d'utilisateur ne peut pas être vide");
		}

		Objects.requireNonNull(roles, "Les rôles ne peuvent pas être null");

		this.roles = Set.copyOf(roles);
	}

	public static UserAccount pending(UUID userID, String lastName, String firstName, String email,
			String phoneNumber) {
		return new UserAccount(userID, null, UserAccountStatus.PENDING_ACTIVATION, Set.of(), null, lastName, firstName,
				email, phoneNumber);
	}

	public static UserAccount pending(UUID userID, UUID employeeID, String lastName, String firstName, String email,
			String phoneNumber) {
		return new UserAccount(userID, null, UserAccountStatus.PENDING_ACTIVATION, Set.of(),
				Objects.requireNonNull(employeeID), lastName, firstName, email, phoneNumber);
	}

	private static String requireIdentityValue(String value, String fieldLabel) {

		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldLabel + " est obligatoire.");
		}

		return value;
	}

	public boolean equals(Object obj) {

		if (!(obj instanceof UserAccount)) {
			return false;
		}

		UserAccount other = (UserAccount) obj;

		if (this.userID.equals(other.userID)) {
			return true;
		}

		return false;
	}

	@Override
	public int hashCode() {
		return userID.hashCode();
	}

	public UUID getUserID() {
		return userID;
	}

	public long getSessionVersion() {
		return sessionVersion;
	}

	public UUID getEmployeeID() {
		return employeeID;
	}

	public Set<UserRole> getRoles() {
		return roles;
	}

	public String getUserName() {
		return userName;
	}

	public UserAccountStatus getStatu() {
		return statu;
	}

	public String getLastName() {
		return lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = requireIdentityValue(email, "L’adresse e-mail");
	}

	public String getPhoneNumber() {
		return phoneNumber;
	}

	public void setPhoneNumber(String phoneNumber) {
		this.phoneNumber = requireIdentityValue(phoneNumber, "Le numéro de téléphone");
	}
}
