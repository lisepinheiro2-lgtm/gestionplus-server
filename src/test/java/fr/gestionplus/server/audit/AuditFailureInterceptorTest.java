package fr.gestionplus.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;

class AuditFailureInterceptorTest {

	@Test
	void failureAuditsNeverCopySecretArgumentsOrExceptionMessages() throws Throwable {
		verifySecretIsolation(new IllegalStateException(secrets()), AuditResult.FAILURE, AuditReason.OPERATION_FAILED);
	}

	@Test
	void denialAuditsNeverCopySecretArgumentsOrExceptionMessages() throws Throwable {
		verifySecretIsolation(new AccessDeniedException(secrets()), AuditResult.DENIED,
				AuditReason.ACCESS_NOT_AUTHORIZED);
	}

	private void verifySecretIsolation(RuntimeException failure, AuditResult result, AuditReason reason)
			throws Throwable {
		UUID actorID = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID targetID = UUID.fromString("00000000-0000-0000-0000-000000000002");
		AuditStore store = mock(AuditStore.class);
		AuditContextProvider context = mock(AuditContextProvider.class);
		when(context.getActorUserId()).thenReturn(actorID);
		when(context.getIpAddress()).thenReturn("192.0.2.10");
		AuditService audit = new AuditService(store, context);
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("audit", audit);
		MethodInvocation invocation = mock(MethodInvocation.class);
		when(invocation.getThis()).thenReturn(new SensitiveOperation());
		when(invocation.getMethod()).thenReturn(SensitiveOperation.class.getDeclaredMethod("change", UUID.class,
				String.class, String.class, String.class, String.class));
		when(invocation.getArguments()).thenReturn(new Object[] { targetID, "private-password", "private-token",
				"654321", "private-recovery-code" });
		when(invocation.proceed()).thenThrow(failure);
		AuditFailureInterceptor interceptor = new AuditFailureInterceptor(beans.getBeanProvider(AuditService.class));

		assertSame(failure, assertThrows(failure.getClass(), () -> interceptor.invoke(invocation)));

		ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
		verify(store).save(event.capture());
		AuditEvent saved = event.getValue();
		assertEquals(actorID, saved.getActorUserId());
		assertEquals(result, saved.getResult());
		assertEquals(reason, saved.getMetadata().reason());
		assertEquals(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, targetID)), saved.getTargets());
		String recorded = saved.getMetadata() + " " + saved.getTargets().stream()
				.map(target -> target.getTargetType() + ":" + target.getTargetId()).toList()
				+ " " + saved.getIpAddress()
				+ " " + saved.getAction() + " " + saved.getActorUserId();

		for (String secret : List.of("private-password", "private-token", "654321", "private-recovery-code")) {
			assertFalse(recorded.contains(secret));
		}

	}

	private String secrets() {
		return "password=private-password token=private-token totp=654321 recovery=private-recovery-code";
	}

	private static class SensitiveOperation {

		@AuditedOperation(value = AuditAction.ACCOUNT_PASSWORD_CHANGE, account = 0)
		public void change(UUID userID, String password, String token, String totp, String recoveryCode) {
		}
	}
}
