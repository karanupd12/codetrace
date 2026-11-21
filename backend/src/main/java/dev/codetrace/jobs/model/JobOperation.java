package dev.codetrace.jobs.model;

/** The kinds of background job; stored by name in {@code index_job.operation}. */
public enum JobOperation {
    SYNC, INDEX, GRAPH_BUILD, IMPACT
}
