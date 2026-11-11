package fr.gestionplus.server.audit;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

final class AuditFailureInterceptor implements MethodInterceptor {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuditFailureInterceptor.class);

	private final ObjectProvider<AuditService> auditServices;

	AuditFailureInterceptor(ObjectProvider<AuditService> auditServices) {
		this.auditServices = auditServices;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {

		try {
			return invocation.proceed();
		} catch (RuntimeException failure) {

			try {
				record(invocation, failure);
			} catch (RuntimeException auditFailure) {
				LOGGER.error("Impossible de tracer l’échec d’une opération de sécurité ({})",
						auditFailure.getClass().getSimpleName());
			}

			throw failure;
		}

	}

	private void record(MethodInvocation invocation, RuntimeException failure) {
		Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), invocation.getThis().getClass());
		AuditedOperation operation = AnnotatedElementUtils.findMergedAnnotation(method, AuditedOperation.class);

		if (operation == null) {
			return;
		}

		Object[] arguments = invocation.getArguments();
		Set<AuditTarget> targets = new HashSet<>();
		add(targets, arguments, operation.account(), AuditTargetType.ACCOUNT);
		add(targets, arguments, operation.structure(), AuditTargetType.ORGANIZATIONAL_STRUCTURE);
		add(targets, arguments, operation.tenant(), AuditTargetType.TENANT_ORGANIZATION);
		add(targets, arguments, operation.employee(), AuditTargetType.EMPLOYEE);
		add(targets, arguments, operation.invitation(), AuditTargetType.INVITATION);

		for (Object argument : arguments) {

			if (argument instanceof AuditTargetSource source) {
				targets.addAll(source.auditTargets());
			}

		}

		AuditResult result = failure instanceof AccessDeniedException || failure instanceof AuthenticationException
				? AuditResult.DENIED
				: AuditResult.FAILURE;

		auditServices.getObject().record(operation.value(), result, targets, AuditMetadata.because(reason(failure)));
	}

	private void add(Set<AuditTarget> targets, Object[] arguments, int index, AuditTargetType type) {

		if (index >= 0 && index < arguments.length && arguments[index] instanceof UUID id) {
			targets.add(new AuditTarget(type, id));
		}

	}

	private AuditReason reason(RuntimeException failure) {

		if (failure instanceof AuditedAccessDeniedException denied) {
			return denied.getReason();
		}

		if (failure instanceof AccessDeniedException) {
			return AuditReason.ACCESS_NOT_AUTHORIZED;
		}

		if (failure instanceof AuthenticationException) {
			return AuditReason.AUTHENTICATION_REQUIRED;
		}

		if (failure instanceof IllegalArgumentException) {
			return AuditReason.INVALID_INPUT;
		}

		return AuditReason.OPERATION_FAILED;
	}
}
