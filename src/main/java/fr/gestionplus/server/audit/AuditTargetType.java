
package fr.gestionplus.server.audit;

public enum AuditTargetType {

	// Utilisateurs et sécurité
	ACCOUNT, EMPLOYEE, INVITATION, AUDIT_EVENT, AUTHORIZED_WORKSTATION,

	// Organisation
	ORGANIZATIONAL_UNIT, ORGANIZATIONAL_STRUCTURE, TENANT_ORGANIZATION,

	// Gestion métier
	CLIENT, INTERVENTION, ABSENCE, REPLACEMENT, PLANNING,

	// Gestion documentaire
	DOCUMENT
}
