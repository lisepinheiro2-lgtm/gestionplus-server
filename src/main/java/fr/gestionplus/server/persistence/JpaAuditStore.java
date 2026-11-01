package fr.gestionplus.server.persistence;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;

@Repository
public class JpaAuditStore implements AuditStore {

	private final AuditEventJpaRepository eventJpaRepository;

	public JpaAuditStore(AuditEventJpaRepository eventJpaRepository) {
		this.eventJpaRepository = Objects.requireNonNull(eventJpaRepository);
	}

	@Override
	public void save(AuditEvent event) {
		AuditEventEntity eventEntity = new AuditEventEntity(event);
		eventJpaRepository.save(eventEntity);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AuditEvent> findById(UUID eventId) {

		Optional<AuditEventEntity> eventEntitys = eventJpaRepository.findById(eventId);

		if (eventEntitys.isEmpty())
			return Optional.empty();

		AuditEventEntity eventEntity = eventEntitys.get();
		AuditAction action = AuditAction.valueOf(eventEntity.getAction());
		AuditResult result = AuditResult.valueOf(eventEntity.getResult());
		Set<AuditTarget> targets = new HashSet<>();

		for (AuditTargetEmbeddable target : eventEntity.getTargets()) {

			AuditTarget auditTarget = new AuditTarget(AuditTargetType.valueOf(target.getTargetType()),
					target.getTargetId());
			targets.add(auditTarget);
		}

		AuditEvent event = new AuditEvent(eventEntity.getOccurredAt(), eventEntity.getEventId(),
				eventEntity.getActorUserId(), action, result, eventEntity.getIpAddress(), targets);

		return Optional.of(event);
	}
}
