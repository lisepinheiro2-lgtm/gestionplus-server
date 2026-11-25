package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SessionValueSerializerTest {

	private final SessionValueSerializer serializer = new SessionValueSerializer();

	@Test
	void preservesOnlyTheRestrictedIdentityVersionAndAbsoluteDeadline() {
		EnrollmentPrincipal principal = new EnrollmentPrincipal(UUID.randomUUID(), 7,
				Instant.parse("2026-11-22T12:10:00Z"));
		var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of(new SimpleGrantedAuthority(EnrollmentPrincipal.AUTHORITY)));
		byte[] encoded = serializer.serialize(new SecurityContextImpl(authentication));
		SecurityContext decoded = (SecurityContext) serializer.deserialize(encoded);
		assertEquals(principal, decoded.getAuthentication().getPrincipal());
		assertEquals(List.of(new SimpleGrantedAuthority(EnrollmentPrincipal.AUTHORITY)),
				List.copyOf(decoded.getAuthentication().getAuthorities()));
		assertNull(decoded.getAuthentication().getCredentials());
		assertNull(decoded.getAuthentication().getDetails());
		assertFalse(new String(encoded, StandardCharsets.UTF_8).contains("ROLE_USER"));
	}

	@Test
	void rejectsBusinessAuthorityOrMfaAssuranceOnAnEnrollmentPrincipal() {
		EnrollmentPrincipal principal = new EnrollmentPrincipal(UUID.randomUUID(), 0, Instant.now());
		var business = UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		assertThrows(SerializationException.class, () -> serializer.serialize(new SecurityContextImpl(business)));
		var assurance = UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of(new SimpleGrantedAuthority(EnrollmentPrincipal.AUTHORITY)));
		assurance.setDetails(new GestionAuthenticationProvider.LoginAssurance(true));
		assertThrows(SerializationException.class, () -> serializer.serialize(new SecurityContextImpl(assurance)));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"type\":\"enrollment-context\",\"userID\":\"00000000-0000-0000-0000-000000000001\",\"version\":0}",
			"{\"type\":\"enrollment-context\",\"userID\":\"00000000-0000-0000-0000-000000000001\",\"version\":-1,\"expiresAt\":\"2026-11-22T12:10:00Z\"}",
			"{\"type\":\"enrollment-context\",\"userID\":\"00000000-0000-0000-0000-000000000001\",\"version\":0,\"expiresAt\":\"invalid\"}",
			"{\"type\":\"enrollment-context\",\"userID\":\"00000000-0000-0000-0000-000000000001\",\"version\":0,\"expiresAt\":\"2026-11-22T12:10:00Z\",\"mfaVerified\":true}" })
	void rejectsMalformedOrPrivilegeExpandingEnrollmentPayloads(String value) {
		assertThrows(SerializationException.class,
				() -> serializer.deserialize(value.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void preservesTheAuthenticatedIdentityAndSecurityVersionWithoutCredentials() {
		UserAccount account = new UserAccount(UUID.randomUUID(), "fictitious-cluster-user",
				UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE), UUID.randomUUID(), "Fictitious", "Test",
				"cluster@example.invalid", "+12025550100", 7, Instant.parse("2026-11-22T12:00:00Z"));
		var authentication = UsernamePasswordAuthenticationToken.authenticated(account, null, List.of());
		authentication.setDetails(new GestionAuthenticationProvider.LoginAssurance(true));
		SecurityContext original = new SecurityContextImpl(authentication);
		byte[] bytes = serializer.serialize(original);
		SecurityContext restored = assertInstanceOf(SecurityContext.class, serializer.deserialize(bytes));
		UserAccount principal = assertInstanceOf(UserAccount.class, restored.getAuthentication().getPrincipal());
		assertEquals(account.getUserID(), principal.getUserID());
		assertEquals(account.getUserName(), principal.getUserName());
		assertEquals(7, principal.getSessionVersion());
		assertEquals(account.getRoles(), principal.getRoles());
		assertNull(principal.getPhoneVerifiedAt());
		assertNull(restored.getAuthentication().getCredentials());
		assertTrue(assertInstanceOf(GestionAuthenticationProvider.LoginAssurance.class,
				restored.getAuthentication().getDetails()).mfaVerified());
		assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("@class"));
	}

	@Test
	void preservesTheCsrfTokenAcrossInstances() {
		CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "fictitious-csrf-token");
		CsrfToken restored = assertInstanceOf(CsrfToken.class, serializer.deserialize(serializer.serialize(token)));
		assertEquals(token.getToken(), restored.getToken());
		assertEquals(token.getHeaderName(), restored.getHeaderName());
		assertEquals(token.getParameterName(), restored.getParameterName());
	}

	@Test
	void preservesTheSessionMetadataTypesAndEventMaps() {
		Map<String, Object> original = Map.of("creationTime", 123456789L, "maxInactiveInterval", 1800,
				"sessionAttr:marker", true, "sessionAttr:value", "example");
		assertEquals(original, serializer.deserialize(serializer.serialize(original)));
		assertNull(serializer.deserialize(null));
		assertNull(serializer.deserialize(serializer.serialize(null)));
	}

	@Test
	void refusesContextsContainingAPassword() {
		var authentication = UsernamePasswordAuthenticationToken.authenticated("fictitious", "private-password",
				List.of());
		var context = new SecurityContextImpl(authentication);
		SerializationException failure = assertThrows(SerializationException.class,
				() -> serializer.serialize(context));
		assertFalse(failure.getMessage().contains("private-password"));
		assertNull(failure.getCause());
	}

	@Test
	void refusesArbitraryJavaTypesAndOversizedValues() {
		assertThrows(SerializationException.class, () -> serializer.serialize(new Object()));
		assertThrows(SerializationException.class, () -> serializer.serialize("x".repeat(65537)));
		assertThrows(SerializationException.class, () -> serializer.deserialize(new byte[65537]));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"type\":\"java.lang.Runtime\",\"value\":{}}",
			"{\"@class\":\"java.lang.Runtime\"}",
			"{\"type\":\"integer\",\"value\":9223372036854775807}",
			"{\"type\":\"boolean\",\"value\":\"true\"}",
			"{\"type\":\"string\",\"value\":\"a\"} {\"type\":\"string\",\"value\":\"b\"}",
			"not-json"
	})
	void refusesInvalidSerializedValues(String value) {
		assertThrows(SerializationException.class,
				() -> serializer.deserialize(value.getBytes(StandardCharsets.UTF_8)));
	}
}
