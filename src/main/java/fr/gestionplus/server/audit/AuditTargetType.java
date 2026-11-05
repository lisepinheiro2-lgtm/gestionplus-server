
package fr.gestionplus.server.audit;

public enum AuditTargetType {

	// Utilisateurs et sécurité
	ACCOUNT, EMPLOYEE, INVITATION, AUDIT_EVENT, AUTHORIZED_WORKSTATION,

	// Organisation
	ORGANIZATIONAL_UNIT,ORGANIZATIONAL_STRUCTURE,

	// Gestion métier
	CLIENT, INTERVENTION, ABSENCE, REPLACEMENT, PLANNING,

	// Gestion documentaire
	DOCUMENT
}
