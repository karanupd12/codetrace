package dev.codetrace.report.markdown;

import java.util.List;

/**
 * Helpers for writing Markdown that contains text from untrusted repositories (file paths, symbol names). Such text is
 * only ever emitted inside a code span with backticks, newlines and pipes neutralised and a length cap, so it cannot
 * inject headings, links, HTML or extra table cells into the report.
 */
public final class MarkdownText {
    private static final int MAX_LEN = 200;

    private MarkdownText() {
    }

    /** Untrusted text as an inline code span that is safe inside a table cell. */
    public static String code(String value) {
        String v = value == null ? "" : value;
        v = v.replace('`', '\'').replace('|', '¦').replaceAll("[\\r\\n\\t]+", " ").trim();
        if (v.length() > MAX_LEN) v = v.substring(0, MAX_LEN - 1) + "…";
        return v.isEmpty() ? "—" : "`" + v + "`";
    }

    /** Untrusted text for the inside of a fenced code block: one line, and unable to close the fence. */
    public static String plain(String value) {
        String v = value == null ? "" : value;
        v = v.replace('`', '\'').replaceAll("[\\r\\n\\t]+", " ").trim();
        return v.length() > MAX_LEN ? v.substring(0, MAX_LEN - 1) + "…" : v;
    }

    /** Trusted, fixed text (our own words) made safe for a table cell. */
    public static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replaceAll("[\\r\\n]+", " ");
    }

    public static String table(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|").append("---|".repeat(header.size())).append('\n');
        for (List<String> row : rows) sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        return sb.toString();
    }
}
