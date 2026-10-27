package fr.gestionplus.server.audit;

import java.util.UUID;

public interface AuditContextProvider {

	UUID getActorUserId();

	String getIpAddress();
}
