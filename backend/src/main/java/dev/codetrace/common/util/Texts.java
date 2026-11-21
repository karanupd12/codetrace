package dev.codetrace.common.util;

/** Text helpers for values that are stored or shown with a length limit. */
public final class Texts {
    private Texts() {
    }

    /** At most {@code max} characters; null becomes an empty string. */
    public static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
