package dev.codetrace.report;

/**
 * One part of the Markdown impact report. Add a section by registering another implementation; the renderer orders
 * sections by {@link #order()} and never needs to change. A section must be a pure function of the report data (no
 * clocks, no randomness), so the same stored analysis always renders to the same document.
 */
public interface ReportSection {
    /** Position in the document; lower comes first. Ties are broken by {@link #id()}. */
    int order();

    String id();

    /** Markdown for this section, ending with a blank line, or an empty string to omit it. */
    String render(ReportData data);
}
