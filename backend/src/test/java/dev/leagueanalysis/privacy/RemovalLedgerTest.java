package dev.leagueanalysis.privacy;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RemovalLedgerTest {
    @TempDir Path directory;
    @Test void durableLedgerContainsOnlyMatchingHashesAndRefusesPublicPermissions() throws Exception {
        Path path = directory.resolve("private/ledger.json");
        var empty = RemovalLedger.empty();
        empty.write(path);
        var entry = new RemovalLedger.Entry(UUID.randomUUID(), Instant.parse("2026-09-11T00:00:00Z").toString(),
                "exclude", Set.of(PrivacyHash.of("test-player")), Set.of(), Set.of(PrivacyHash.of("NA1_100")));
        empty.append(entry).write(path);
        assertThat(RemovalLedger.read(path).entries()).containsExactly(entry);
        assertThat(Files.readString(path)).doesNotContain("test-player", "NA1_100");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> RemovalLedger.read(path)).hasMessageContaining("PRIVATE_LEDGER_PERMISSIONS");
    }
    @Test void missingCorruptAndSymlinkLedgersFailClosed() throws Exception {
        Path path = directory.resolve("ledger.json");
        assertThatThrownBy(() -> RemovalLedger.read(path)).hasMessageContaining("LEDGER");
        RemovalLedger.empty().write(path);
        Files.writeString(path, "{}");
        assertThatThrownBy(() -> RemovalLedger.read(path)).hasMessageContaining("LEDGER");
        var link = directory.resolve("link.json");
        Files.createSymbolicLink(link, path);
        assertThatThrownBy(() -> RemovalLedger.read(link)).hasMessageContaining("LEDGER");
    }
}
