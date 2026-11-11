package fr.gestionplus.server.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import tools.jackson.databind.json.JsonMapper;

final class EncryptedAuditCodec {

	static final int MAX_BYTES = 65536;
	private static final byte VERSION = 1;
	private static final int NONCE_BYTES = 12;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();
	private final JsonMapper json = JsonMapper.builder().build();

	EncryptedAuditCodec(String base64Key) {
		byte[] decoded = Base64.getDecoder().decode(base64Key);

		if (decoded.length != 32) {
			throw new IllegalArgumentException("La clé du journal d’audit doit contenir 256 bits.");
		}

		key = new SecretKeySpec(decoded, "AES");
	}

	byte[] encrypt(AuditEvent event) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		byte[] cleartext = json.writeValueAsBytes(Envelope.from(event));

		try {
			byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, event.getEventId()).doFinal(cleartext);
			int size = 1 + nonce.length + ciphertext.length;

			if (size > MAX_BYTES) {
				throw new IllegalArgumentException("Taille maximale d’un événement d’audit dépassée.");
			}

			return ByteBuffer.allocate(size).put(VERSION).put(nonce).put(ciphertext).array();
		} catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Chiffrement du journal d’audit impossible.", failure);
		}

	}

	AuditEvent decrypt(UUID eventId, byte[] encrypted) {

		if (encrypted.length < 1 + NONCE_BYTES + 16 || encrypted.length > MAX_BYTES || encrypted[0] != VERSION) {
			throw new IllegalArgumentException("Format d’événement d’audit invalide.");
		}

		ByteBuffer buffer = ByteBuffer.wrap(encrypted);
		buffer.get();
		byte[] nonce = new byte[NONCE_BYTES];
		buffer.get(nonce);
		byte[] ciphertext = new byte[buffer.remaining()];
		buffer.get(ciphertext);

		try {
			byte[] cleartext = cipher(Cipher.DECRYPT_MODE, nonce, eventId).doFinal(ciphertext);
			Envelope envelope = json.readValue(cleartext, Envelope.class);

			if (!eventId.equals(envelope.eventId())) {
				throw new IllegalArgumentException("Identifiant du journal d’audit incohérent.");
			}

			return envelope.toEvent();
		} catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Événement d’audit de secours illisible ou altéré.", failure);
		}

	}

	String metadataJson(AuditMetadata metadata) {
		return metadata.equals(AuditMetadata.empty()) ? null : json.writeValueAsString(metadata);
	}

	AuditMetadata readMetadata(String metadata) {
		return metadata == null ? AuditMetadata.empty() : json.readValue(metadata, AuditMetadata.class);
	}

	private Cipher cipher(int mode, byte[] nonce, UUID eventId) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(128, nonce));
		cipher.updateAAD(("gestionplus-audit-v" + VERSION + ":" + eventId).getBytes(StandardCharsets.US_ASCII));
		return cipher;
	}

	record Target(AuditTargetType type, UUID id) {
	}

	record Envelope(UUID eventId, long seconds, int nanos, UUID actorId, UUID tenantId, AuditAction action,
			AuditResult result, String ipAddress, List<Target> targets, AuditMetadata metadata) {

		static Envelope from(AuditEvent event) {
			return new Envelope(event.getEventId(), event.getOccurredAt().getEpochSecond(),
					event.getOccurredAt().getNano(),
					event.getActorUserId(), event.getTenantId(), event.getAction(), event.getResult(),
					event.getIpAddress(),
					event.getTargets().stream().map(target -> new Target(target.getTargetType(), target.getTargetId()))
							.toList(),
					event.getMetadata());
		}

		AuditEvent toEvent() {
			return new AuditEvent(Instant.ofEpochSecond(seconds, nanos), eventId, actorId, action, result, ipAddress,
					targets.stream().map(target -> new AuditTarget(target.type(), target.id()))
							.collect(Collectors.toSet()),
					metadata, tenantId);
		}
	}
}
