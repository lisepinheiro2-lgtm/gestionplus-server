package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class MailDeliveryExecutorTest {

	private final MailDeliveryExecutor executor = new MailDeliveryExecutor();

	@Test
	void refusesAFourthTaskWhileThreeAreRunning() throws Exception {
		CountDownLatch started = new CountDownLatch(3);
		CountDownLatch release = new CountDownLatch(1);

		for (int index = 0; index < 3; index++) {
			assertTrue(executor.trySubmit(() -> {
				started.countDown();
				await(release);
			}));
		}

		assertTrue(started.await(5, TimeUnit.SECONDS));
		assertFalse(executor.trySubmit(() -> {
		}));
		release.countDown();
		awaitFreeSlot();
	}

	@Test
	void releasesTheSlotAfterTheTaskCompletes() throws Exception {
		CountDownLatch done = new CountDownLatch(1);

		assertTrue(executor.trySubmit(done::countDown));
		assertTrue(done.await(5, TimeUnit.SECONDS));
		awaitFreeSlot();
	}

	@Test
	void releasesTheSlotWhenTheTaskFails() throws Exception {
		CountDownLatch failed = new CountDownLatch(1);

		assertTrue(executor.trySubmit(() -> {
			failed.countDown();
			throw new RuntimeException("échec simulé");
		}));
		assertTrue(failed.await(5, TimeUnit.SECONDS));
		awaitFreeSlot();
	}

	private void awaitFreeSlot() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;

		while (!executor.trySubmit(() -> {
		})) {

			if (System.currentTimeMillis() > deadline) {
				fail("jeton jamais rendu");
			}

			Thread.sleep(10);
		}

	}

	private void await(CountDownLatch latch) {

		try {
			latch.await();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}

	}
}
