package dev.leagueanalysis.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Pseudonymous matching keys, not anonymization; keep the ledger private. */
public final class PrivacyHash {
    private PrivacyHash() {}

    public static String of(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String riotId(String name, String tag) {
        return of(name.toLowerCase(Locale.ROOT) + "#" + tag.toLowerCase(Locale.ROOT));
    }
}
