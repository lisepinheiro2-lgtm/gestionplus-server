package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditMetadata;

class AuthenticationSecretExposureTest {

	@Test
	void authenticationObjectsDoNotExposeTheirSecretsThroughToString() {
		String setupKey = "private-setup-key";
		String recoveryCode = "private-recovery-code";
		String passwordHash = "private-password-hash";
		String totp = "654321";
		UUID userID = UUID.randomUUID();
		var enrollment = new AccountMfaService.Enrollment(UUID.randomUUID(), setupKey, Instant.now());
		var recovery = new AccountMfaService.RecoveryCodes(List.of(recoveryCode));
		var details = new GestionAuthenticationProvider.LoginDetails("192.0.2.10", totp);
		var credential = new UserCredential(userID, passwordHash);

		assertFalse(enrollment.toString().contains(setupKey));
		assertFalse(recovery.toString().contains(recoveryCode));
		assertFalse(details.toString().contains(totp));
		assertFalse(credential.toString().contains(passwordHash));
	}

	@Test
	void addingFreeTextToTheAuditSchemaRequiresAnExplicitReview() {
		Set<String> metadata = Arrays.stream(AuditMetadata.class.getRecordComponents())
				.map(component -> component.getName()).collect(Collectors.toSet());
		Set<String> freeText = Arrays.stream(AuditEvent.class.getDeclaredFields())
				.filter(field -> field.getType() == String.class).map(Field::getName).collect(Collectors.toSet());

		assertEquals(Set.of("grantedRoles", "revokedRoles", "reason"), metadata);
		assertEquals(Set.of("ipAddress"), freeText);
	}
}
