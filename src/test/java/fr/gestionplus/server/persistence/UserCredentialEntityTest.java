package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import fr.gestionplus.server.auth.UserCredential;

public class UserCredentialEntityTest {

	@Test
	void preservesCredentialDuringConversion() {
		UUID userId = UUID.randomUUID();
		String passwordHash = "empreinteFictive";
		UserCredential credential = new UserCredential(userId, passwordHash);
		UserCredentialEntity credentialEntity = new UserCredentialEntity(credential);
		UserCredential restored = credentialEntity.toDomain();

		assertEquals(credential.getUserId(), restored.getUserId());
		assertEquals(credential.getPasswordHash(), restored.getPasswordHash());
	}
}
