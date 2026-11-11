package fr.gestionplus.server.audit;

import java.util.Set;

public interface AuditTargetSource {

	Set<AuditTarget> auditTargets();
}
