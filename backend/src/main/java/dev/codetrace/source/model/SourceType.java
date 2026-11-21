package dev.codetrace.source.model;

public enum SourceType {
    /** Existing Git repository on the server, under an operator-configured root. */
    LOCAL,
    /** Public HTTPS Git URL on an allowlisted host; cloned by the worker. */
    GIT_URL
}
