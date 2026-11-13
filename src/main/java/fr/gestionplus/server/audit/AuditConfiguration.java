package fr.gestionplus.server.audit;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Configuration
public class AuditConfiguration {

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public Advisor auditFailureAdvisor(ObjectProvider<AuditService> auditServices) {
		DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
				new AnnotationMatchingPointcut(null, AuditedOperation.class, true),
				new AuditFailureInterceptor(auditServices));
		advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return advisor;
	}

	@Bean
	public AuditService auditService(AuditPersistence persistence, AuditContextProvider contextProvider,
			OrganizationalStructureStore structures, TenantOrganizationStore tenants,
			StructureMembershipStore memberships) {
		return new AuditService(persistence::write, contextProvider, structures, tenants, memberships);
	}
}
