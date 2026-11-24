package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import fr.gestionplus.server.account.AccountNotificationDelivery;
import fr.gestionplus.server.account.AccountNotificationStore;
import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.account.MailDeliveryExecutor;
import fr.gestionplus.server.api.MfaEnrollmentRoutes;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///fictitious_mfa_enrollment_http",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"gestionplus.mfa.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
@AutoConfigureMockMvc
class MfaEnrollmentHttpTest extends AuditStorageIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
	private static final String PASSWORD = "Fictitious-Enrollment-Password-2026!";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AccountMfaService accountMfaService;
	@Autowired
	private AccountMfaStore accountMfaStore;
	@Autowired
	private AccountStore accountStore;
	@Autowired
	private SessionLifetime sessionLifetime;
	@Autowired
	private MfaSecretCipher cipher;
	@Autowired
	private AccountNotificationStore notificationStore;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoBean
	private Clock clock;
	@MockitoBean
	private RequestRateLimiter rateLimiter;
	@MockitoBean
	private CompromisedPasswordChecker compromisedPasswordChecker;
	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	private UUID userID;
	private String userName;
	private String proof;

	@BeforeEach
	void prepareAnActivatedFictitiousAccountWhoseMfaIsRequired() {
		when(clock.instant()).thenAnswer(call -> now.get());
		when(compromisedPasswordChecker.isCompromised(anyString())).thenReturn(false);
		when(rateLimiter.acquire(anyString(), anyString(), anyInt(), any()))
				.thenReturn(new RequestRateLimiter.Decision(true, Duration.ZERO));
		when(rateLimiter.acquireForAccount(anyString(), any(), anyInt(), any()))
				.thenReturn(new RequestRateLimiter.Decision(true, Duration.ZERO));
		userID = UUID.randomUUID();
		userName = "fictitious_mfa_" + userID;
		jdbc.update("""
				INSERT INTO user_accounts (user_id, user_name, status, user_first_name, user_last_name,
				    user_email, user_phone_number)
				VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Enrollment', ?, '+12025550100')
				""", userID, userName, userID + "@example.invalid");
		UUID tenantID = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, 'Fictitious MFA', ?)",
				tenantID, userID);
		jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
		jdbc.update("INSERT INTO user_credentials (user_id, password_hash) VALUES (?, ?)", userID,
				Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD));
		proof = accountMfaService.issueEnrollmentProof(userID, 0);
	}

	@Test
	void activatesAnInvitedAccountOverHttpBeforeOpeningItsRestrictedMfaContext() throws Exception {
		UUID tenantID = jdbc.queryForObject("SELECT tenant_id FROM tenant_organizations WHERE super_admin_user_id = ?",
				UUID.class, userID);
		UUID structureID = UUID.randomUUID();
		UUID employeeID = UUID.randomUUID();
		UUID invitedUserID = UUID.randomUUID();
		UUID invitationID = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) VALUES (?, 'Fictitious activation', ?)",
				structureID, tenantID);
		jdbc.update(
				"INSERT INTO employees (employee_id, first_name, last_name, contract_hours, structure_id) VALUES (?, 'Fictitious', 'Invited', 35, ?)",
				employeeID, structureID);
		jdbc.update(
				"""
						INSERT INTO user_accounts (user_id, employee_id, status, user_first_name, user_last_name, user_email, user_phone_number)
						VALUES (?, ?, 'PENDING_ACTIVATION', 'Fictitious', 'Invited', ?, '+12025550100')
						""",
				invitedUserID, employeeID, invitedUserID + "@example.invalid");
		jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", invitedUserID, tenantID);
		jdbc.update("INSERT INTO structure_memberships (user_id, structure_id, tenant_id) VALUES (?, ?, ?)",
				invitedUserID, structureID, tenantID);
		jdbc.update("INSERT INTO structure_membership_roles (user_id, structure_id, role) VALUES (?, ?, 'R_H')",
				invitedUserID, structureID);
		byte[] tokenBytes = new byte[32];
		new SecureRandom().nextBytes(tokenBytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
		String digest = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
		jdbc.update(
				"INSERT INTO account_invitations (invitation_id, user_id, structure_id, token_digest, expires_at) VALUES (?, ?, ?, ?, ?)",
				invitationID, invitedUserID, structureID, digest,
				OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
		AtomicReference<String> smsCode = new AtomicReference<>();
		doAnswer(call -> {
			smsCode.set(call.getArgument(1));
			return null;
		}).when(messageSender).send(anyString(), anyString());
		Csrf anonymous = csrf(null);
		String path = "/api/invitations/" + invitationID;
		mockMvc.perform(
				jsonPost(path + "/sms-challenge", anonymous, JSON.createObjectNode().put("token", token).toString()))
				.andExpect(status().isOk());
		userID = invitedUserID;
		userName = "fictitious_invited_" + invitedUserID;
		String response = mockMvc.perform(jsonPost(path + "/activation", anonymous,
				JSON.createObjectNode().put("token", token).put("proof", smsCode.get()).put("userName", userName)
						.put("password", PASSWORD).toString()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		proof = JSON.readTree(response).get("enrollmentProof").asString();
		assertTrue(proof.matches("[A-Za-z0-9_-]{22}"));
		MockHttpSession restrictedSession = openSession();
		Csrf restricted = csrf(restrictedSession);
		JsonNode enrollment = begin(restricted);
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH, restricted,
				JSON.createObjectNode().put("enrollmentID", enrollment.get("enrollmentID").asString())
						.put("code", enrollmentCode(enrollment)).toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.codes.length()").value(10));
		assertTrue(restrictedSession.isInvalid());
		now.set(NOW.plusSeconds(30));
		byte[] secret = cipher.decrypt(userID, null,
				accountMfaStore.findFactor(userID).orElseThrow().encryptedSecret());

		try {
			Csrf loginCsrf = csrf(null);
			mockMvc.perform(login(loginCsrf, TotpCodes.atStep(secret, now.get().getEpochSecond() / 30)))
					.andExpect(status().isOk());
			mockMvc.perform(get("/api/auth/me").session(loginCsrf.session())).andExpect(status().isOk());
		} finally {
			java.util.Arrays.fill(secret, (byte) 0);
		}

	}

	@Test
	void completesRestrictedEnrollmentThenRequiresANormalLoginWithANewTotp() throws Exception {
		Csrf anonymous = csrf(null);
		mockMvc.perform(login(anonymous, null)).andExpect(status().isUnauthorized());
		MockHttpSession session = openSession();
		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/structures").session(session)).andExpect(status().isForbidden());
		Csrf restricted = csrf(session);
		JsonNode enrollment = begin(restricted);
		assertEquals(enrollment, begin(restricted));
		assertEquals(NOW.plusSeconds(600), Instant.parse(enrollment.get("expiresAt").asString()));
		String code = enrollmentCode(enrollment);
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH, restricted,
				JSON.createObjectNode().put("enrollmentID", enrollment.get("enrollmentID").asString()).put("code", code)
						.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.codes.length()").value(10));
		assertTrue(session.isInvalid());
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM account_deactivation_notifications
				WHERE kind = 'MFA_ENROLLED' AND actor_user_id = ? AND target_id = ?
				  AND recipient_user_id = ? AND structure_id IS NULL
				""", Integer.class, userID, userID, userID));
		mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(login(csrf(null), code)).andExpect(status().isUnauthorized());
		Thread.sleep(500);
		now.set(NOW.plusSeconds(30));
		Csrf next = csrf(null);
		byte[] secret = cipher.decrypt(userID, null,
				accountMfaStore.findFactor(userID).orElseThrow().encryptedSecret());

		try {
			String nextCode = TotpCodes.atStep(secret, now.get().getEpochSecond() / 30);
			var login = mockMvc.perform(login(next, nextCode)).andReturn();
			assertEquals(200, login.getResponse().getStatus(), () -> jdbc.queryForList(
					"SELECT result, metadata FROM audit_events WHERE action = 'AUTH_LOGIN' ORDER BY occurred_at DESC LIMIT 3")
					.toString());
			mockMvc.perform(get("/api/auth/me").session(next.session()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.userName").value(userName));
		} finally {
			java.util.Arrays.fill(secret, (byte) 0);
		}

	}

	@Test
	void aNotificationInsertFailureRollsBackMfaThePendingEnrollmentAndTheSecurityVersion() throws Exception {
		MockHttpSession session = openSession();
		JsonNode enrollment = begin(csrf(session));
		UUID enrollmentID = UUID.fromString(enrollment.get("enrollmentID").asString());
		String code = enrollmentCode(enrollment);
		long version = accountStore.findById(userID).orElseThrow().getSessionVersion();
		jdbc.execute("ALTER TABLE account_deactivation_notifications ADD CONSTRAINT fictitious_reject_mfa_notification "
				+ "CHECK (kind <> 'MFA_ENROLLED' OR target_id <> '" + userID + "'::uuid)");

		try {
			assertThrows(DataIntegrityViolationException.class,
					() -> accountMfaService.completeEnrollment(userID, enrollmentID, code));

			assertTrue(accountMfaStore.findFactor(userID).isEmpty());
			assertEquals(enrollmentID, accountMfaStore.findEnrollment(userID).orElseThrow().enrollmentID());
			assertEquals(version, accountStore.findById(userID).orElseThrow().getSessionVersion());
			assertFalse(session.isInvalid());
			assertEquals(0, jdbc.queryForObject(
					"SELECT COUNT(*) FROM account_deactivation_notifications WHERE target_id = ?", Integer.class,
					userID));
		} finally {
			jdbc.execute(
					"ALTER TABLE account_deactivation_notifications DROP CONSTRAINT fictitious_reject_mfa_notification");
		}

	}

	@Test
	void smtpFailureAfterCommitLeavesTheMfaActivatedAndTheNotificationAvailableForRetry() throws Exception {
		MockHttpSession session = openSession();
		Csrf restricted = csrf(session);
		JsonNode enrollment = begin(restricted);
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH, restricted,
				JSON.createObjectNode().put("enrollmentID", enrollment.get("enrollmentID").asString())
						.put("code", enrollmentCode(enrollment)).toString()))
				.andExpect(status().isOk());
		long version = accountStore.findById(userID).orElseThrow().getSessionVersion();
		jdbc.update(
				"""
						UPDATE account_deactivation_notifications SET next_attempt_at = TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'
						WHERE target_id = ? AND kind = 'MFA_ENROLLED'
						""",
				userID);
		JavaMailSender mailSender = mock(JavaMailSender.class);
		doThrow(new MailSendException("Panne SMTP fictive après activation MFA."))
				.when(mailSender).send(any(SimpleMailMessage.class));
		MailDeliveryExecutor executor = mock(MailDeliveryExecutor.class);
		doAnswer(call -> {
			((Runnable) call.getArgument(0)).run();
			return true;
		}).when(executor).trySubmit(any());
		var delivery = new AccountNotificationDelivery(notificationStore, accountStore, mailSender,
				"fictitious@example.invalid", mock(AuditService.class), transactionManager, executor);

		delivery.deliverNext();

		verify(mailSender).send(any(SimpleMailMessage.class));
		assertTrue(accountMfaStore.findFactor(userID).isPresent());
		assertTrue(accountMfaStore.findEnrollment(userID).isEmpty());
		assertEquals(version, accountStore.findById(userID).orElseThrow().getSessionVersion());
		assertTrue(session.isInvalid());
		assertEquals(1, jdbc.queryForObject("""
				SELECT attempts FROM account_deactivation_notifications WHERE target_id = ? AND kind = 'MFA_ENROLLED'
				""", Integer.class, userID));
	}

	@Test
	void consumesTheProofAtSessionCreationAndNeverRequiresItAgainForBegin() throws Exception {
		MockHttpSession first = openSession();
		Csrf replay = csrf(null);
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH, replay,
				JSON.createObjectNode().put("proof", proof).toString())).andExpect(status().isForbidden());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_mfa_enrollment_proofs WHERE user_id = ?",
				Integer.class, userID));
		assertTrue(begin(csrf(first)).has("setupKey"));
	}

	@Test
	void csrfReadsAndRepeatedBeginsCannotExtendTheAbsoluteTenMinuteLifetime() throws Exception {
		MockHttpSession session = openSession();
		now.set(NOW.plusSeconds(599));
		JsonNode enrollment = begin(csrf(session));
		assertEquals(NOW.plusSeconds(600), Instant.parse(enrollment.get("expiresAt").asString()));
		now.set(NOW.plusSeconds(600));
		mockMvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isUnauthorized());
		assertTrue(session.isInvalid());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void invalidatesRestrictedSessionsWhenTheAccountIsDisabledOrItsVersionChanges(boolean disable) throws Exception {
		MockHttpSession session = openSession();
		jdbc.update(disable ? "UPDATE user_accounts SET status = 'DISABLED' WHERE user_id = ?"
				: "UPDATE user_accounts SET session_version = session_version + 1 WHERE user_id = ?", userID);
		mockMvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isUnauthorized());
		assertTrue(session.isInvalid());
	}

	@Test
	void anExistingBusinessSessionCannotBeReplacedByAnEnrollmentContext() throws Exception {
		MockHttpSession session = new MockHttpSession();
		var authentication = UsernamePasswordAuthenticationToken.authenticated(
				accountStore.findById(userID).orElseThrow(),
				null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(authentication));
		sessionLifetime.start(session, true);
		Csrf token = csrf(session);
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH, token,
				JSON.createObjectNode().put("proof", proof).toString())).andExpect(status().isForbidden());
		assertFalse(session.isInvalid());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account_mfa_enrollment_proofs WHERE user_id = ?",
				Integer.class, userID));
	}

	@Test
	void keepsCsrfProtectionAndAllowsExplicitCancellation() throws Exception {
		Csrf token = csrf(null);
		mockMvc.perform(post(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH).session(token.session())
				.contentType("application/json").content(JSON.createObjectNode().put("proof", proof).toString()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		MockHttpSession session = openSession();
		mockMvc.perform(post(MfaEnrollmentRoutes.ENROLLMENT_PATH).session(session)).andExpect(status().isForbidden());
		Csrf restricted = csrf(session);
		mockMvc.perform(post("/logout").session(session).header(restricted.header(), restricted.token()))
				.andExpect(status().isNoContent());
		assertTrue(session.isInvalid());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "malformed", "aaaaaaaaaaaaaaaaaaaaaa" })
	void rejectsInvalidProofsWithoutCreatingAnAuthenticatedContext(String value) throws Exception {
		Csrf token = csrf(null);
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH, token,
				JSON.createObjectNode().put("proof", value).toString())).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/auth/me").session(token.session())).andExpect(status().isUnauthorized());
	}

	private MockHttpSession openSession() throws Exception {
		Csrf token = csrf(null);
		String previousID = token.session().getId();
		mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH, token,
				JSON.createObjectNode().put("proof", proof).toString())).andExpect(status().isNoContent());
		assertFalse(previousID.equals(token.session().getId()));
		return token.session();
	}

	private JsonNode begin(Csrf token) throws Exception {
		return JSON.readTree(mockMvc.perform(jsonPost(MfaEnrollmentRoutes.ENROLLMENT_PATH, token, "{}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private String enrollmentCode(JsonNode enrollment) {
		UUID enrollmentID = UUID.fromString(enrollment.get("enrollmentID").asString());
		byte[] secret = cipher.decrypt(userID, enrollmentID,
				accountMfaStore.findEnrollment(userID).orElseThrow().encryptedSecret());

		try {
			return TotpCodes.atStep(secret, now.get().getEpochSecond() / 30);
		} finally {
			java.util.Arrays.fill(secret, (byte) 0);
		}

	}

	private Csrf csrf(MockHttpSession session) throws Exception {
		var request = get("/api/auth/csrf");

		if (session != null) {
			request.session(session);
		}

		var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
		return new Csrf((MockHttpSession) result.getRequest().getSession(false), body.get("headerName").asString(),
				body.get("token").asString());
	}

	private MockHttpServletRequestBuilder jsonPost(String path, Csrf csrf, String body) {
		return post(path).session(csrf.session()).header(csrf.header(), csrf.token()).contentType("application/json")
				.content(body);
	}

	private MockHttpServletRequestBuilder login(Csrf csrf, String code) {
		var request = post("/login").session(csrf.session()).header(csrf.header(), csrf.token())
				.param("username", userName).param("password", PASSWORD)
				.contentType("application/x-www-form-urlencoded");
		return code == null ? request : request.param("totp", code);
	}

	private record Csrf(MockHttpSession session, String header, String token) {
	}
}
