package dev.codetrace.jobs.model;

/** A stage of a background job, reported as it happens. The message shown to people is stored with each event. */
public enum JobStep {
    DOWNLOADING, VERSIONS_FOUND,
    READING, READ, SAVING,
    LOADING, BUILDING_MAP, SAVING_MAP, CHECKING_MAP, MAP_READY,
    COMPARING, CHANGES_FOUND, TRACING, SCORING, WRITING_REPORT,
    DONE, RETRYING, FAILED
}
