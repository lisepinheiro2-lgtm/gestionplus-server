package fr.gestionplus.server.account;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SuccessionTransaction {

	private final TransactionTemplate transaction;

	public SuccessionTransaction(PlatformTransactionManager manager) {
		this.transaction = new TransactionTemplate(manager);
	}

	/** Return a result for a committed revision or closure. Map any refusal only after this method returns. */
	public <T> T execute(Supplier<T> work) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Une opération de succession doit ouvrir sa propre transaction.");
		}
		return transaction.execute(status -> work.get());
	}
}
