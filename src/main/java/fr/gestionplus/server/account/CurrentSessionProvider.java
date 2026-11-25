package fr.gestionplus.server.account;

public interface CurrentSessionProvider {

	String getCurrentSessionIDHash();

	String getActiveSessionIDHash();
}
