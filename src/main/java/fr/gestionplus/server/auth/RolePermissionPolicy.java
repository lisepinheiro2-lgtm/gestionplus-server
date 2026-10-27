package fr.gestionplus.server.auth;

import java.security.AllPermission;

public class RolePermissionPolicy {

	public static boolean allows(UserRole role, Permission permission) {

		if (role == null || permission == null) {
			return false;
		}

		switch (role) {

		case ADMIN:
			return true;

		case MANAGER:
			switch (permission) {

			// Contrats
			case CREATE_CONTRACT, EDIT_CONTRACT,

					// Planning et interventions
					VIEW_TEAM_SCHEDULE, CREATE_INTERVENTION, EDIT_INTERVENTION, CANCEL_INTERVENTION,
					ASSIGN_INTERVENTION, MANAGE_ABSENCES, MANAGE_REPLACEMENTS,

					// Clients
					CREATE_CLIENT, VIEW_CLIENT_PUBLIC_PROFILE, EDIT_CLIENT_PUBLIC_PROFILE,

					// Salariés
					CREATE_EMPLOYEE, VIEW_EMPLOYEE_PROFESSIONAL_PROFILE, EDIT_EMPLOYEE_PROFESSIONAL_PROFILE,

					// Documents ordinaires
					CREATE_DOCUMENT, VIEW_DOCUMENT, EDIT_DOCUMENT,

					// Archives
					ARCHIVE_CLIENT, VIEW_ARCHIVED_CLIENTS, RESTORE_CLIENT, ARCHIVE_EMPLOYEE, VIEW_ARCHIVED_EMPLOYEES,
					RESTORE_EMPLOYEE:
				return true;

			default:
				return false;
			}

		case SECRETARY:
			switch (permission) {

			// Contrats : création de brouillons
			case CREATE_CONTRACT,

					// Planning et interventions
					VIEW_TEAM_SCHEDULE, CREATE_INTERVENTION, EDIT_INTERVENTION, ASSIGN_INTERVENTION,
					MANAGE_REPLACEMENTS,

					// Clients
					CREATE_CLIENT, VIEW_CLIENT_PUBLIC_PROFILE, EDIT_CLIENT_PUBLIC_PROFILE,

					// Salariés
					CREATE_EMPLOYEE, VIEW_EMPLOYEE_PROFESSIONAL_PROFILE, EDIT_EMPLOYEE_PROFESSIONAL_PROFILE,

					// Documents ordinaires
					CREATE_DOCUMENT, VIEW_DOCUMENT, EDIT_DOCUMENT,

					// Documents sensibles : dépôt uniquement
					UPLOAD_SENSITIVE_DOCUMENT,

					// Facturation : brouillons uniquement
					CREATE_INVOICE, EDIT_DRAFT_INVOICE:
				return true;

			default:
				return false;
			}

		case EMPLOYEE:
			switch (permission) {

			case VIEW_OWN_SCHEDULE, VIEW_ASSIGNED_CLIENT_PUBLIC_PROFILE, VIEW_OWN_PAYSLIP:
				return true;

			default:
				return false;
			}
			
		case TEAM_LEAD :
			switch (permission) {

			case VIEW_TEAM_SCHEDULE:
				return true;

			default:
				return false;
			}

		case ACCOUNTANT:
			switch (permission) {

			// Comptabilité et facturation
			case VIEW_ACCOUNTING, CREATE_INVOICE, EDIT_DRAFT_INVOICE, ISSUE_INVOICE, ISSUE_CREDIT_NOTE,

					// Paie si cette mission lui est confiée
					PREPARE_PAYROLL, MANAGE_PAYROLL:
				return true;

			default:
				return false;
			}

		default:
			return false;
		}
	}

}
