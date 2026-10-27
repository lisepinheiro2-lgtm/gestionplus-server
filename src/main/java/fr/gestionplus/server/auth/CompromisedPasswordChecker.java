package fr.gestionplus.server.auth;

public interface CompromisedPasswordChecker {

	boolean isCompromised(String rawPassword);
}
