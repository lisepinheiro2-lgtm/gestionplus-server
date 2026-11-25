package fr.gestionplus.server.auth;

import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.MFA_ENROLLMENT;
import static fr.gestionplus.server.api.MfaEnrollmentRoutes.VOLUNTARY_BEGIN_PATH;
import static fr.gestionplus.server.api.MfaEnrollmentRoutes.VOLUNTARY_COMPLETE_PATH;
import static fr.gestionplus.server.api.MfaEnrollmentRoutes.VOLUNTARY_REQUEST_PATH;
import static fr.gestionplus.server.auth.AccountMfaStore.EnrollmentPurpose.RECOVERY;
import static fr.gestionplus.server.auth.AccountMfaStore.EnrollmentPurpose.VOLUNTARY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.gestionplus.server.account.AccountNotificationStore;
import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.account.ActivationMessageDeliveryException;
import fr.gestionplus.server.account.SmsIdentityChallengeStore;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentState;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///fictitious_voluntary_mfa_http",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"gestionplus.mfa.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
@AutoConfigureMockMvc
class VoluntaryMfaEnrollmentHttpTest extends AuditStorageIntegrationTest {

	private static final String PHONE = "+12025550100";
	private static final String PASSWORD = "Fictitious-voluntary-Password-2026!";
	private static final String PASSWORD_SCOPE = "mfa-voluntary-enrollment-password";
	private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	private final AtomicReference<String> smsCode = new AtomicReference<>();

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private MfaSecretCipher cipher;
	@Autowired
	private SessionLifetime lifetime;
	@Autowired
	private SmsIdentityChallengeStore challenges;
	@MockitoSpyBean
	private AccountMfaStore mfaStore;
	@MockitoSpyBean
	private AccountNotificationStore notifications;
	@MockitoSpyBean
	private AccountStore accounts;
	@MockitoSpyBean
	private RequestRateLimiter limiter;
	@MockitoBean
	private Clock clock;

	private UUID userID;
	private String userName;

	@BeforeEach
	void createAccountWithNoRoleRequirement() {
		when(clock.instant()).thenAnswer(call -> now.get());
		userID = UUID.randomUUID();
		userName = "fictitious_voluntary_" + userID;
		jdbc.update("""
				INSERT INTO user_accounts (user_id, user_name, status, user_first_name, user_last_name,
				    user_email, user_phone_number, phone_verified_at)
				VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Voluntary', ?, ?, ?)
				""", userID, userName, userID + "@example.invalid", PHONE, NOW.atOffset(ZoneOffset.UTC));
		jdbc.update("INSERT INTO user_credentials (user_id, password_hash) VALUES (?, ?)", userID,
				Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD));
		doAnswer(call -> {
			smsCode.set(call.getArgument(2));
			return null;
		}).when(messageSender).send(eq(MFA_ENROLLMENT), eq(PHONE), anyString());
	}

	@Test
	void enrollsOnlyInTheOriginatingSessionAndRevokesEverySession() throws Exception {
		Csrf first = login();
		Csrf other = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		begin(other, smsCode.get()).andExpect(status().isForbidden());
		assertEquals(0, challenges.findCurrentMfaByUser(userID).orElseThrow().failedAttempts());
		UUID enrollmentID = enrollment(first);
		assertEquals(SessionIDHasher.hash(first.session().getId()),
				mfaStore.findEnrollment(userID, VOLUNTARY).orElseThrow().sessionIDHash());
		begin(first, smsCode.get()).andExpect(status().isForbidden());

		String code = totp(enrollmentID);
		complete(other, enrollmentID, code).andExpect(status().isForbidden());
		complete(first, enrollmentID, code).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.codes.length()").value(10));
		assertTrue(first.session().isInvalid());
		assertTrue(mfaStore.findEnrollment(userID, VOLUNTARY).isEmpty());
		assertEquals(1, accounts.findById(userID).orElseThrow().getSessionVersion());
		mvc.perform(get("/api/auth/me").session(other.session())).andExpect(status().isUnauthorized());

		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM account_deactivation_notifications
				WHERE actor_user_id = ? AND target_id = ? AND recipient_user_id = ? AND kind = 'MFA_ENROLLED'
				""", Integer.class, userID, userID, userID));
		assertEquals(1, auditCount("MFA_VOLUNTARY_SMS_REQUEST", "SUCCESS"));
		assertEquals(1, auditCount("MFA_VOLUNTARY_ENROLLMENT_BEGIN", "SUCCESS"));
		assertEquals(1, auditCount("MFA_VOLUNTARY_ENROLLMENT_COMPLETE", "SUCCESS"));
		assertEquals(1, auditCount("MFA_VOLUNTARY_ENROLLMENT_COMPLETE", "DENIED"));
	}

	@Test
	void appliesTheCooldownAndSlidingSmsLimitAcrossSessions() throws Exception {
		Csrf first = login();
		Csrf other = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		now.set(NOW.plusSeconds(59));
		request(other, PASSWORD).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "1"));
		now.set(NOW.plusSeconds(60));
		request(other, PASSWORD).andExpect(status().isNoContent());
		now.set(NOW.plusSeconds(120));
		request(first, PASSWORD).andExpect(status().isNoContent());
		now.set(NOW.plusSeconds(121));
		request(other, PASSWORD).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "779")).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
		assertEquals(3, challenges.findRecentMfaIssuances(userID, NOW.minusSeconds(1), 10).size());
	}

	@Test
	void countsWrongCodesPersistsTheirRevocationAndRejectsExpiry() throws Exception {
		Csrf first = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		String rejected = smsCode.get().equals("000000") ? "000001" : "000000";
		begin(first, rejected).andExpect(status().isForbidden());
		assertEquals(1, challenges.findCurrentMfaByUser(userID).orElseThrow().failedAttempts());
		begin(first, "malformed").andExpect(status().isForbidden());
		begin(first, rejected).andExpect(status().isForbidden());
		assertTrue(challenges.findCurrentMfaByUser(userID).isEmpty());
		begin(first, smsCode.get()).andExpect(status().isForbidden());

		now.set(NOW.plusSeconds(60));
		request(first, PASSWORD).andExpect(status().isNoContent());
		now.set(NOW.plusSeconds(360));
		begin(first, smsCode.get()).andExpect(status().isForbidden());
		assertTrue(mfaStore.findEnrollment(userID, VOLUNTARY).isEmpty());
	}

	@Test
	void passwordAndTotpBudgetsAreIndependentAndRejectBeforeLocking() throws Exception {
		Csrf first = login();
		Csrf other = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		UUID enrollmentID = enrollment(first);
		for (int attempt = 0; attempt < 4; attempt++) {
			request(other, "fictitious-wrong-password").andExpect(status().isForbidden());
		}
		clearInvocations(accounts);
		request(first, PASSWORD).andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
		verify(accounts, never()).findByIdForUpdate(userID);

		for (int attempt = 0; attempt < 5; attempt++) {
			complete(first, enrollmentID, "malformed").andExpect(status().isForbidden());
		}
		clearInvocations(accounts);
		complete(other, enrollmentID, totp(enrollmentID)).andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
		verify(accounts, never()).findByIdForUpdate(userID);
		assertFalse(mfaStore.hasState(userID));
	}

	@Test
	void rollsBackSmsConsumptionWhenTotpPreparationFails() throws Exception {
		Csrf first = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		doThrow(new DataAccessResourceFailureException("Fictitious database failure"))
				.doCallRealMethod().when(mfaStore).saveEnrollment(any());
		begin(first, smsCode.get()).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		assertTrue(challenges.findCurrentMfaByUser(userID).isPresent());
		assertTrue(mfaStore.findEnrollment(userID, VOLUNTARY).isEmpty());
		assertEquals(1, auditCount("MFA_VOLUNTARY_ENROLLMENT_BEGIN", "FAILURE"));
		enrollment(first);
	}

	@Test
	void rollsBackFactorAndSessionRevocationWhenNotificationCannotBeQueued() throws Exception {
		Csrf first = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		UUID enrollmentID = enrollment(first);
		String code = totp(enrollmentID);
		AccountNotificationStore notificationTarget = AopTestUtils.getUltimateTargetObject(notifications);
		doThrow(new DataAccessResourceFailureException("Fictitious database failure"))
				.doCallRealMethod().when(notificationTarget).enqueueMfaEnrollment(userID);

		complete(first, enrollmentID, code).andExpect(status().isServiceUnavailable());
		assertFalse(first.session().isInvalid());
		assertFalse(mfaStore.hasState(userID));
		assertEquals(0, accounts.findById(userID).orElseThrow().getSessionVersion());
		assertTrue(mfaStore.findEnrollment(userID, VOLUNTARY).isPresent());
		complete(first, enrollmentID, code).andExpect(status().isOk());
	}

	@Test
	void refusesToUseAnUnverifiedOrChangedNumber() throws Exception {
		Csrf oldSession = login();
		jdbc.update("UPDATE user_accounts SET user_phone_number = ? WHERE user_id = ?", "+12025550101", userID);
		request(oldSession, PASSWORD).andExpect(status().isUnauthorized());
		assertFalse(accounts.findById(userID).orElseThrow().isPhoneVerified());
		request(login(), PASSWORD).andExpect(status().isForbidden());
		assertTrue(challenges.findCurrentMfaByUser(userID).isEmpty());
	}

	@Test
	void anAbandonedOrExpiredEnrollmentDoesNotRequireMfaAtLogin() throws Exception {
		Csrf first = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		UUID enrollmentID = enrollment(first);
		login();
		now.set(NOW.plusSeconds(600));
		complete(first, enrollmentID, totp(enrollmentID)).andExpect(status().isForbidden());
		login();
		assertFalse(mfaStore.hasState(userID));
	}

	@Test
	void cannotOverwriteRecoveryOrCompleteItThroughTheVoluntaryEndpoint() throws Exception {
		Csrf first = login();
		request(first, PASSWORD).andExpect(status().isNoContent());
		UUID enrollmentID = enrollment(first);
		String code = totp(enrollmentID);
		UUID recoveryID = UUID.randomUUID();
		jdbc.update("INSERT INTO account_mfa_state (user_id, last_accepted_step) VALUES (?, -1)", userID);
		mfaStore.saveEnrollment(new EnrollmentState(recoveryID, userID, new byte[] {1}, 0, NOW.plusSeconds(600),
				RECOVERY, null));
		lifetime.start(first.session(), true);

		request(first, PASSWORD).andExpect(status().isForbidden());
		complete(first, enrollmentID, code).andExpect(status().isForbidden());
		complete(first, recoveryID, code).andExpect(status().isForbidden());
		assertEquals(recoveryID, mfaStore.findEnrollment(userID, RECOVERY).orElseThrow().enrollmentID());
		assertTrue(mfaStore.findFactor(userID).isPresent());
	}

	@Test
	void failsClosedWhenTheSharedLimiterIsUnavailableAndAuditsTheFailure() throws Exception {
		Csrf first = login();
		doThrow(new DistributedSecurityUnavailableException()).when(limiter).acquireForAccount(
				eq(PASSWORD_SCOPE), eq(userID), eq(5), eq(Duration.ofMinutes(10)));
		clearInvocations(accounts);
		request(first, PASSWORD).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		verify(accounts, never()).findByIdForUpdate(userID);
		assertTrue(challenges.findCurrentMfaByUser(userID).isEmpty());
		assertEquals(1, auditCount("MFA_VOLUNTARY_SMS_REQUEST", "FAILURE"));
	}

	@Test
	void unavailableSmsIsRevokedWithoutRefundingItsCooldown() throws Exception {
		Csrf first = login();
		doThrow(new ActivationMessageDeliveryException(MFA_ENROLLMENT)).when(messageSender)
				.send(eq(MFA_ENROLLMENT), eq(PHONE), anyString());
		request(first, PASSWORD).andExpect(status().isServiceUnavailable());
		assertTrue(challenges.findCurrentMfaByUser(userID).isEmpty());
		assertEquals(1, auditCount("MFA_VOLUNTARY_SMS_REQUEST", "FAILURE"));
		request(first, PASSWORD).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "60"));
	}

	@Test
	void requiresAuthenticationAndCsrf() throws Exception {
		request(csrf(null), PASSWORD).andExpect(status().isUnauthorized());
		Csrf first = login();
		mvc.perform(post(VOLUNTARY_REQUEST_PATH).session(first.session()).contentType("application/json")
				.content(JSON.createObjectNode().put("password", PASSWORD).toString()))
				.andExpect(status().isForbidden());
		assertTrue(challenges.findCurrentMfaByUser(userID).isEmpty());
	}

	private Csrf login() throws Exception {
		Csrf csrf = csrf(null);
		mvc.perform(post("/login").session(csrf.session()).header(csrf.header(), csrf.token())
				.contentType("application/x-www-form-urlencoded").param("username", userName).param("password", PASSWORD))
				.andExpect(status().isOk());
		return csrf(csrf.session());
	}

	private Csrf csrf(MockHttpSession session) throws Exception {
		var request = get("/api/auth/csrf");
		if (session != null) {
			request.session(session);
		}
		var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
		JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
		return new Csrf((MockHttpSession) result.getRequest().getSession(false), body.get("headerName").asString(),
				body.get("token").asString());
	}

	private ResultActions request(Csrf csrf, String password) throws Exception {
		return jsonPost(VOLUNTARY_REQUEST_PATH, csrf, JSON.createObjectNode().put("password", password));
	}

	private ResultActions begin(Csrf csrf, String sms) throws Exception {
		return jsonPost(VOLUNTARY_BEGIN_PATH, csrf, JSON.createObjectNode().put("code", sms));
	}

	private ResultActions complete(Csrf csrf, UUID enrollmentID, String code) throws Exception {
		return jsonPost(VOLUNTARY_COMPLETE_PATH, csrf,
				JSON.createObjectNode().put("enrollmentID", enrollmentID.toString()).put("code", code));
	}

	private ResultActions jsonPost(String path, Csrf csrf, JsonNode body) throws Exception {
		return mvc.perform(post(path).session(csrf.session()).header(csrf.header(), csrf.token())
				.contentType("application/json").content(body.toString()));
	}

	private UUID enrollment(Csrf csrf) throws Exception {
		String body = begin(csrf, smsCode.get()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("enrollmentID").asString());
	}

	private String totp(UUID enrollmentID) {
		byte[] secret = cipher.decrypt(userID, enrollmentID,
				mfaStore.findEnrollment(userID, VOLUNTARY).orElseThrow().encryptedSecret());
		try {
			return TotpCodes.atStep(secret, now.get().getEpochSecond() / 30);
		} finally {
			Arrays.fill(secret, (byte) 0);
		}
	}

	private int auditCount(String action, String result) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND action = ? AND result = ?
				""", Integer.class, userID, action, result);
	}

	private record Csrf(MockHttpSession session, String header, String token) {
	}
}
