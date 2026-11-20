package dev.codetrace.repository;

public enum SourceType {
    /** Existing Git repository on the server, under an operator-configured root. */
    LOCAL,
    /** Public HTTPS Git URL on an allowlisted host; cloned by the worker. */
    GIT_URL
}
