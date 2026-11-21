package dev.codetrace.jobs.model;

/** Job lifecycle states; stored by name in {@code index_job.status}. */
public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, DEAD_LETTERED;

    public static boolean isFailed(String status) {
        return FAILED.name().equals(status) || DEAD_LETTERED.name().equals(status);
    }

    public static boolean isActive(String status) {
        return QUEUED.name().equals(status) || RUNNING.name().equals(status);
    }
}
