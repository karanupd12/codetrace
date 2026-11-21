package dev.codetrace.jobs;

/** Failure during sync/index with a stable category. Messages are safe to store and show (no server paths). */
public class JobFailureException extends RuntimeException {
    public enum Category {
        INVALID_SOURCE(false), LIMIT_EXCEEDED(false), TIMEOUT(false), REPOSITORY_UNAVAILABLE(false),
        REVISION_NOT_FOUND(false), NO_COMMITS(false), GRAPH_INVALID(false), GIT_ERROR(true), INTERNAL(true);

        private final boolean retryable;

        Category(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Category category;

    public JobFailureException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public JobFailureException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** A message safe to store for any failure: the categorised message, or only the type of an unexpected error. */
    public static String safeReason(RuntimeException e) {
        return e instanceof JobFailureException f ? f.getMessage() : "Unexpected error (" + e.getClass().getSimpleName() + ").";
    }
}
