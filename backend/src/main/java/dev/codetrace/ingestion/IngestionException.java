package dev.codetrace.ingestion;

/** Failure during sync/index with a stable category. Messages are safe to store and show (no server paths). */
public class IngestionException extends RuntimeException {
    public enum Category {
        INVALID_SOURCE(false), LIMIT_EXCEEDED(false), TIMEOUT(false), REPOSITORY_UNAVAILABLE(false),
        REVISION_NOT_FOUND(false), NO_COMMITS(false), GIT_ERROR(true), INTERNAL(true);

        private final boolean retryable;

        Category(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Category category;

    public IngestionException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public IngestionException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
