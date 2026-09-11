package dev.leagueanalysis.privacy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Authoritative intent log outside database backups. Never publish or serve this file. */
public record RemovalLedger(int version, UUID ledgerId, List<Entry> entries) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");

    public RemovalLedger {
        if (version != 1 || ledgerId == null || entries == null) throw new IllegalArgumentException("INVALID_LEDGER");
        entries = List.copyOf(entries);
        if (entries.stream().map(Entry::operationId).distinct().count() != entries.size())
            throw new IllegalArgumentException("DUPLICATE_LEDGER_OPERATION");
    }
    public static RemovalLedger empty() { return new RemovalLedger(1, UUID.randomUUID(), List.of()); }
    public RemovalLedger append(Entry entry) {
        var next = new ArrayList<>(entries); next.add(entry);
        return new RemovalLedger(version, ledgerId, next);
    }
    public String encoded() { return JSON.writeValueAsString(this) + "\n"; }
    public String digest() { return PrivacyHash.of(encoded()); }

    public static RemovalLedger read(Path path) throws IOException {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 5_000_000)
                throw new IOException("PRIVATE_LEDGER_MISSING_OR_INVALID");
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE))
                throw new IOException("PRIVATE_LEDGER_PERMISSIONS_MUST_BE_0600");
            return JSON.readValue(Files.readString(path), RemovalLedger.class);
        } catch (RuntimeException error) { throw new IOException("INVALID_PRIVATE_LEDGER", error); }
    }

    public void write(Path path) throws IOException {
        byte[] content=encoded().getBytes(StandardCharsets.UTF_8);
        if(content.length>5_000_000) throw new IOException("PRIVATE_LEDGER_TOO_LARGE");
        path = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)) throw new IOException("PRIVATE_LEDGER_SYMLINK_REFUSED");
        Path parent = path.getParent();
        Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(parent)) throw new IOException("PRIVATE_LEDGER_DIRECTORY_SYMLINK_REFUSED");
        Path temporary = Files.createTempFile(parent, ".ledger-", ".tmp", PosixFilePermissions.asFileAttribute(PRIVATE));
        try {
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public record Entry(UUID operationId, String recordedAt, String mode, Set<String> puuidHashes,
                        Set<String> aliasHashes, Set<String> matchHashes) {
        public Entry {
            if (operationId == null || recordedAt == null || !Set.of("erase-only", "exclude").contains(mode))
                throw new IllegalArgumentException("INVALID_LEDGER_ENTRY");
            Instant.parse(recordedAt);
            puuidHashes = hashes(puuidHashes); aliasHashes = hashes(aliasHashes); matchHashes = hashes(matchHashes);
            if (puuidHashes.isEmpty()) throw new IllegalArgumentException("LEDGER_REQUIRES_VERIFIED_SUBJECT");
        }
        private static Set<String> hashes(Set<String> values) {
            if (values == null || values.stream().anyMatch(value -> value == null || !value.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("INVALID_LEDGER_HASH");
            return Collections.unmodifiableSortedSet(new TreeSet<>(values));
        }
    }
}
