package fr.gestionplus.server.audit;

import java.util.Optional;
import java.util.UUID;

public interface AuditStore {

	void save(AuditEvent event);

	Optional<AuditEvent> findById(UUID eventId);
}
