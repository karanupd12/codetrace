package dev.codetrace.ingestion.indexer;

import dev.codetrace.ingestion.parser.JavaSymbolExtractor.Diagnostic;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.FileExtraction;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.FileStatus;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Parses one file at a time on a worker thread, so a file that takes too long can be given up on. The parser cannot be
 * interrupted, so an abandoned parse keeps running on its daemon thread; a fresh parser and thread take over, and after
 * {@link #MAX_ABANDONED} abandoned files the job stops rather than leave more threads behind.
 */
final class BoundedParser implements AutoCloseable {
    static final int MAX_ABANDONED = 3;
    static final String PARSE_TIMEOUT = "PARSE_TIMEOUT";

    private final Supplier<Function<String, FileExtraction>> parsers;
    private Function<String, FileExtraction> parser;
    private ExecutorService pool;
    private int abandoned;

    BoundedParser(Supplier<Function<String, FileExtraction>> parsers) {
        this.parsers = parsers;
        this.parser = parsers.get();
        this.pool = newPool();
    }

    /** The file's facts, or a {@link TimeoutException} when parsing takes longer than {@code budgetNanos}. */
    FileExtraction parse(String source, long budgetNanos) throws TimeoutException {
        Function<String, FileExtraction> current = parser;
        Future<FileExtraction> result = pool.submit(() -> current.apply(source));
        try {
            return result.get(Math.max(1, budgetNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            result.cancel(true);
            pool.shutdownNow();
            if (++abandoned > MAX_ABANDONED) {
                throw new JobFailureException(Category.LIMIT_EXCEEDED,
                        "More than " + MAX_ABANDONED + " files took too long to parse.");
            }
            parser = parsers.get();
            pool = newPool();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailureException(Category.TIMEOUT, "Indexing was interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        }
    }

    /** What is recorded for a file that took longer than {@code seconds} to parse. */
    static FileExtraction timedOut(int seconds) {
        return new FileExtraction(FileStatus.FAILED, "", List.of(), List.of(), List.of(), List.of(new Diagnostic("ERROR",
                PARSE_TIMEOUT, "Parsing took longer than " + seconds + " s, so the file was skipped.", 0)), List.of());
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private static ExecutorService newPool() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "java-parse");
            t.setDaemon(true);
            return t;
        });
    }
}
