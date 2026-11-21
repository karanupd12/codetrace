package dev.codetrace.common.util;

/** Escaping for SQL LIKE patterns; pair with {@code like :param escape '\'}. */
public final class SqlLike {
    private SqlLike() {
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** {@code %text%} with LIKE wildcards in the text escaped. */
    public static String contains(String text) {
        return "%" + escape(text) + "%";
    }
}
