package fr.gestionplus.server.account;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class MailDeliveryExecutor {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailDeliveryExecutor.class);
	private static final int MAX_CONCURRENT_SENDS = 3;

	private final Semaphore slots = new Semaphore(MAX_CONCURRENT_SENDS);
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	public boolean trySubmit(Runnable task) {

		if (!slots.tryAcquire()) {
			return false;
		}

		try {
			workers.execute(() -> {

				try {
					task.run();
				} catch (RuntimeException failure) {
					LOGGER.warn("Envoi délégué interrompu ({})", failure.getClass().getSimpleName());
				} finally {
					slots.release();
				}

			});
		} catch (RejectedExecutionException rejection) {
			slots.release();
			return false;
		}

		return true;
	}

	@PreDestroy
	void shutdown() {
		workers.close();
	}

}
