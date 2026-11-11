package fr.gestionplus.server.audit;

public interface AuditFallback {

	void writeInBusinessTransaction(AuditEvent event);

	void writeIndependent(AuditEvent event);
}
