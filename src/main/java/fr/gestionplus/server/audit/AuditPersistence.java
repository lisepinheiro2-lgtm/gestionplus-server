package fr.gestionplus.server.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AuditPersistence {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuditPersistence.class);

	private final AuditStore auditStore;
	private final ObjectProvider<AuditFallback> fallbacks;
	private final TransactionTemplate independentTransaction;

	public AuditPersistence(AuditStore auditStore, ObjectProvider<AuditFallback> fallbacks,
			PlatformTransactionManager transactionManager) {
		this.auditStore = auditStore;
		this.fallbacks = fallbacks;
		independentTransaction = new TransactionTemplate(transactionManager);
		independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void write(AuditEvent event) {
		AuditFallback fallback = fallbacks.getIfAvailable();

		if (event.getResult() == AuditResult.SUCCESS && TransactionSynchronizationManager.isActualTransactionActive()) {

			if (fallback == null) {
				auditStore.save(event);
			} else {

				try {
					fallback.writeInBusinessTransaction(event);
				} catch (RuntimeException failure) {
					LOGGER.error("Échec de l’audit transactionnel obligatoire pour l’événement {} ({})",
							event.getEventId(), failure.getClass().getSimpleName());
					throw failure;
				}

			}

			return;
		}

		try {
			independentTransaction.executeWithoutResult(transaction -> auditStore.save(event));
		} catch (RuntimeException primaryFailure) {
			LOGGER.warn("Audit PostgreSQL indisponible pour l’événement {} ({})", event.getEventId(),
					primaryFailure.getClass().getSimpleName());

			if (fallback != null) {

				try {
					fallback.writeIndependent(event);
					return;
				} catch (RuntimeException fallbackFailure) {
					LOGGER.error("Échec du journal d’audit de secours pour l’événement {} ({})", event.getEventId(),
							fallbackFailure.getClass().getSimpleName());
				}

			} else {
				LOGGER.error("Aucun journal d’audit de secours configuré pour l’événement {}", event.getEventId());
			}

			if (event.getResult() == AuditResult.SUCCESS) {
				throw new IllegalStateException("Audit obligatoire indisponible.");
			}

		}

	}
}
