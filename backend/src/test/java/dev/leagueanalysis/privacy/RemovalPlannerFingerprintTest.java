package dev.leagueanalysis.privacy;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RemovalPlannerFingerprintTest {
    @Test void preservesV1Utf8EncodingCharacterLengthsAndDelimiters() throws Exception {
        // Independently calculated SHA-256 of the v1 text, including UTF-16 row
        // lengths and the standard UTF-8 replacement for unpaired surrogates.
        assertThat(fingerprint(Set.of("subject-z", "subject-a"), Set.of("alias-a"), Set.of(),
                List.of("source_payload", "{\"label\":\"Å玩家🦊\\n\"}", "", "a:b\nc", "\ud800", "x\udc00")))
                .isEqualTo("6bce75513952f36be64a4a9f11e8b3a2b4126a880d92cb8c48c8a1042485b15e");
    }

    @Test void sortsHashGroupsAndInvalidatesEveryFingerprintInput() throws Exception {
        var subjects = new LinkedHashSet<>(List.of("subject-z", "subject-a"));
        var aliases = Set.of("alias-a");
        var matches = Set.of("match-a");
        var rows = List.of("source_payload", "first", "second");
        var expected = fingerprint(subjects, aliases, matches, rows);
        assertThat(fingerprint(new LinkedHashSet<>(List.of("subject-a", "subject-z")), aliases, matches, rows))
                .isEqualTo(expected);
        assertThat(fingerprint(Set.of("subject-b"), aliases, matches, rows)).isNotEqualTo(expected);
        assertThat(fingerprint(subjects, Set.of("alias-b"), matches, rows)).isNotEqualTo(expected);
        assertThat(fingerprint(subjects, aliases, Set.of("match-b"), rows)).isNotEqualTo(expected);
        assertThat(fingerprint(subjects, aliases, matches, List.of("source_capture", "first", "second"))).isNotEqualTo(expected);
        assertThat(fingerprint(subjects, aliases, matches, List.of("source_payload", "second", "first"))).isNotEqualTo(expected);
        assertThat(fingerprint(subjects, aliases, matches, List.of("source_payload", "first", "changed"))).isNotEqualTo(expected);
    }

    @Test void fingerprints128MiBOfRowsWithin32MiBHeap() throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LowHeapProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("constrained-heap fingerprint completes").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(output).isZero();
            assertThat(output.strip()).isEqualTo("d20a3b0c2038b8585dcf9169f621fe5ae7635eb882066a4196504b2f703d7d27");
        } finally {
            process.destroyForcibly();
        }
    }

    private static String fingerprint(Set<String> subjects, Set<String> aliases, Set<String> matches, List<String> rows)
            throws Exception {
        var method = RemovalPlanner.class.getDeclaredMethod("fingerprint", Set.class, Set.class, Set.class, List.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, subjects, aliases, matches, rows);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    public static final class LowHeapProbe {
        public static void main(String[] args) throws Exception {
            System.out.println(fingerprint(Set.of(), Set.of(), Set.of(),
                    Collections.nCopies(512, "x".repeat(256 * 1024))));
        }
    }
}
