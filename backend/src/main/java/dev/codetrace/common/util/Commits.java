package dev.codetrace.common.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Git commit id helpers. Only full 40-character SHA-1 ids are accepted. */
public final class Commits {
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");

    private Commits() {
    }

    public static boolean isFullSha1(String value) {
        return value != null && SHA1.matcher(value.trim()).matches();
    }

    /** Trimmed, lower-case form of a full commit id. Call only after {@link #isFullSha1}. */
    public static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** The usual 7-character abbreviation; safe for null or short input. */
    public static String shortHash(String commit) {
        return commit == null ? "" : commit.substring(0, Math.min(7, commit.length()));
    }
}
