package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;

class ActivationCodeGeneratorTest {

	@Test
	void generatesCodesContainingExactlySixDigits() {
		ActivationCodeGenerator generator = new ActivationCodeGenerator();

		for (int index = 0; index < 100; index++) {
			assertTrue(generator.generate().matches("[0-9]{6}"));
		}

	}

	@ParameterizedTest
	@CsvSource({ "0,000000", "7,000007", "42,000042", "999999,999999" })
	void preservesSixDigitsAtTheBoundsAndWithLeadingZeros(int value, String expectedCode) {

		try (MockedConstruction<SecureRandom> randoms = mockConstruction(SecureRandom.class,
				(random, context) -> when(random.nextInt(1_000_000)).thenReturn(value))) {
			ActivationCodeGenerator generator = new ActivationCodeGenerator();

			assertEquals(expectedCode, generator.generate());
			verify(randoms.constructed().getFirst()).nextInt(1_000_000);
		}

	}

	@Test
	void drawsANewRandomValueForEachCode() {

		try (MockedConstruction<SecureRandom> randoms = mockConstruction(SecureRandom.class,
				(random, context) -> when(random.nextInt(1_000_000)).thenReturn(7, 42))) {
			ActivationCodeGenerator generator = new ActivationCodeGenerator();

			assertEquals("000007", generator.generate());
			assertEquals("000042", generator.generate());
			verify(randoms.constructed().getFirst(), times(2)).nextInt(1_000_000);
		}

	}
}
