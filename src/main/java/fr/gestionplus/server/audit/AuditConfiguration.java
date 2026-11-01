package fr.gestionplus.server.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditConfiguration {

	@Bean
	public AuditService auditService(AuditStore auditStore, AuditContextProvider contextProvider) {
		return new AuditService(auditStore, contextProvider);
	}
}
