package fr.gestionplus.server.account;

import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;

public interface SmsMessageSender {

	void send(Purpose purpose, String phoneNumber, String code);

	static String formatMessage(Purpose purpose, String code) {
		String label = switch (purpose) {
		case ACCOUNT_ACTIVATION -> "Code d'activation";
		case MFA_ENROLLMENT -> "Code de configuration MFA";
		};
		return "Gestion+ - " + label + " : " + code + ". Ne le communiquez a personne.";
	}

	default void send(String phoneNumber, String code) {
		send(Purpose.ACCOUNT_ACTIVATION, phoneNumber, code);
	}
}
