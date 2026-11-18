package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserAccountTest {

	private static final String PHONE_NUMBER = "+12025550100";

	@ParameterizedTest
	@ValueSource(strings = { "+1", PHONE_NUMBER, "+123456789012345" })
	void keepsValidPhoneNumberAtCreation(String phoneNumber) {
		UserAccount account = createAccount(phoneNumber);

		assertEquals(phoneNumber, account.getPhoneNumber());
	}

	@ParameterizedTest
	@MethodSource("invalidPhoneNumbers")
	void rejectsInvalidPhoneNumberAtCreation(String phoneNumber) {
		assertThrows(IllegalArgumentException.class, () -> createAccount(phoneNumber));
	}

	@Test
	void updatesValidPhoneNumber() {
		UserAccount account = createAccount(PHONE_NUMBER);
		String newPhoneNumber = "+12025550101";

		account.setPhoneNumber(newPhoneNumber);

		assertEquals(newPhoneNumber, account.getPhoneNumber());
	}

	@ParameterizedTest
	@MethodSource("invalidPhoneNumbers")
	void keepsPreviousPhoneNumberWhenUpdateIsRejected(String phoneNumber) {
		UserAccount account = createAccount(PHONE_NUMBER);

		assertThrows(IllegalArgumentException.class, () -> account.setPhoneNumber(phoneNumber));
		assertEquals(PHONE_NUMBER, account.getPhoneNumber());
	}

	private static Stream<String> invalidPhoneNumbers() {
		return Stream.of(null, "", "  ", "0600000000", "+", "+1234567890123456", "+1 2025550100",
				"+1202555010A", "+١٢٣", "+12025550100 ");
	}

	private UserAccount createAccount(String phoneNumber) {
		return new UserAccount(UUID.randomUUID(), "test-user", UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", phoneNumber);
	}
}
