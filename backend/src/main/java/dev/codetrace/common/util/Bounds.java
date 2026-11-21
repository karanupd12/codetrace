package dev.codetrace.common.util;

/** Clamping of client-supplied sizes so every query and traversal stays bounded. */
public final class Bounds {
    private Bounds() {
    }

    /** {@code value} limited to [min, max]. */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** A page size between 1 and {@code max}. */
    public static int limit(int requested, int max) {
        return clamp(requested, 1, max);
    }

    /** A non-negative offset. */
    public static int offset(int requested) {
        return Math.max(0, requested);
    }
}
