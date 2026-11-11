package fr.gestionplus.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

final class AuditFileJournal {

	private final Path directory;
	private final int maxEvents;
	private final boolean posix;

	AuditFileJournal(Path directory, int maxEvents) {
		this.directory = directory.toAbsolutePath().normalize();
		this.maxEvents = maxEvents;

		try {

			if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)
					|| !this.directory.equals(this.directory.toRealPath()) || !Files.isWritable(this.directory)
					|| maxEvents < 1) {
				throw new IllegalArgumentException("Répertoire ou capacité du journal d’audit invalide.");
			}

			posix = Files.getFileAttributeView(this.directory, PosixFileAttributeView.class) != null;

			if (posix
					&& Files.getPosixFilePermissions(this.directory).stream().anyMatch(AuditFileJournal::otherAccess)) {
				throw new IllegalArgumentException("Le répertoire du journal d’audit doit être privé.");
			}

		} catch (IOException failure) {
			throw new UncheckedIOException("Répertoire du journal d’audit inaccessible.", failure);
		}

	}

	synchronized void append(UUID eventId, byte[] payload) {
		Path destination = directory.resolve(eventId + ".audit");
		Path pending = null;

		try {

			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalStateException("Événement d’audit de secours déjà présent.");
			}

			try (Stream<Path> files = Files.list(directory)) {

				if (files.filter(this::isJournalEntry).limit(maxEvents).count() >= maxEvents) {
					throw new IllegalStateException("Le journal d’audit de secours est saturé.");
				}

			}

			pending = Files.createTempFile(directory, "audit-", ".pending");

			try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(payload);

				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}

				channel.force(true);
			}

			Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
			pending = null;

			if (posix) {

				try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
					channel.force(true);
				}

			}

		} catch (IOException failure) {
			throw new UncheckedIOException("Écriture du journal d’audit de secours impossible.", failure);
		} finally {

			if (pending != null) {

				try {
					Files.deleteIfExists(pending);
				} catch (IOException cleanupFailure) {
					throw new UncheckedIOException("Entrée temporaire du journal d’audit non nettoyée.",
							cleanupFailure);
				}

			}

		}

	}

	synchronized List<Path> entries() {

		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(this::isJournalEntry).sorted().limit(maxEvents).toList();
		} catch (IOException failure) {
			throw new UncheckedIOException("Lecture du journal d’audit de secours impossible.", failure);
		}

	}

	byte[] read(Path entry) {

		try (InputStream input = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) {
			byte[] payload = input.readNBytes(EncryptedAuditCodec.MAX_BYTES + 1);

			if (payload.length > EncryptedAuditCodec.MAX_BYTES) {
				throw new IllegalArgumentException("Entrée du journal d’audit trop volumineuse.");
			}

			return payload;
		} catch (IOException failure) {
			throw new UncheckedIOException("Entrée du journal d’audit inaccessible.", failure);
		}

	}

	void remove(Path entry) {

		try {
			Files.deleteIfExists(entry);
		} catch (IOException failure) {
			throw new UncheckedIOException("Entrée réconciliée du journal d’audit non nettoyée.", failure);
		}

	}

	private boolean isJournalEntry(Path entry) {
		String filename = entry.getFileName().toString();
		return filename.endsWith(".audit") || filename.endsWith(".pending");
	}

	private static boolean otherAccess(PosixFilePermission permission) {
		return !permission.name().startsWith("OWNER_");
	}
}
