package fr.gestionplus.server.account;

public interface ActivationMessageSender {

	void send(String phoneNumber, String code);
}
