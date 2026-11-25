package fr.gestionplus.server.api;

public final class MfaEnrollmentRoutes {

	public static final String ENROLLMENT_PATH = "/api/auth/mfa/enrollment";
	public static final String ENROLLMENT_COMPLETE_PATH = "/api/auth/mfa/enrollment/complete";
	public static final String ENROLLMENT_SESSION_PATH = "/api/auth/mfa/enrollment/session";

	public static final String VOLUNTARY_REQUEST_PATH = "/api/auth/mfa/voluntary-enrollment/sms-challenge";
	public static final String VOLUNTARY_BEGIN_PATH = "/api/auth/mfa/voluntary-enrollment";
	public static final String VOLUNTARY_COMPLETE_PATH = "/api/auth/mfa/voluntary-enrollment/complete";

	private MfaEnrollmentRoutes() {
	}
}
