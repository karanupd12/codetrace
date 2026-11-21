package dev.codetrace.common.util;

/** Conventions for repository-relative source paths. */
public final class SourcePaths {
    private SourcePaths() {
    }

    /** The module a path belongs to: the part before {@code /src/}, or "" for sources at the repository root. */
    public static String moduleOf(String path) {
        int i = path.indexOf("/src/");
        return i >= 0 ? path.substring(0, i) : "";
    }

    /** {@link #moduleOf} for display: the root module is shown as "(root)". */
    public static String displayModule(String path) {
        String module = moduleOf(path);
        return module.isEmpty() ? "(root)" : module;
    }

    public static boolean isTestPath(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }
}
