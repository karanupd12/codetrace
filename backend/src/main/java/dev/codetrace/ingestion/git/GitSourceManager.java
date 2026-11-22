package dev.codetrace.ingestion.git;

import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.source.service.SourceValidator;
import dev.codetrace.common.web.ApiException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Opens repositories for reading. Remote repositories are fetched into a bare, depth-limited workspace; local ones are
 * opened in place, read-only. Nothing is ever checked out, so no working-tree files, hooks, filters or symlinks exist
 * to be triggered, and nothing from the repository is executed.
 *
 * <p>Remote transfers never follow HTTP redirects (a redirect could point past the host checks), are cancelled as soon
 * as the workspace grows beyond the size limit or the time limit passes, and are skipped when the workspace already
 * holds every commit the caller needs.
 */
@Component
public class GitSourceManager {
    private static final Logger log = LoggerFactory.getLogger(GitSourceManager.class);
    /** How often the size of a growing workspace is measured during a transfer. */
    private static final long SIZE_CHECK_NANOS = Duration.ofSeconds(1).toNanos();

    static {
        // Resolved addresses are kept for a fixed, short time, so a transfer started right after the host check
        // connects to the addresses that were just checked instead of resolving the name again.
        Security.setProperty("networkaddress.cache.ttl", "30");
    }

    private final IngestionProperties props;
    private final SourceValidator validator;

    public GitSourceManager(IngestionProperties props, SourceValidator validator) {
        this.props = props;
        this.validator = validator;
    }

    /** Opens the repository, fetching new commits first when it is remote. The caller must close it. */
    public Repository open(SourceRepository repo) {
        return open(repo, List.of());
    }

    /**
     * Opens the repository. A remote repository is fetched only when its workspace is missing or lacks one of
     * {@code requiredCommits}; with no required commits it is always fetched. The caller must close it.
     */
    public Repository open(SourceRepository repo, Collection<String> requiredCommits) {
        try {
            return repo.sourceType() == SourceType.GIT_URL ? openRemote(repo, requiredCommits) : openLocal(repo);
        } catch (ApiException e) {
            throw new JobFailureException(Category.INVALID_SOURCE, e.getMessage());
        } catch (IOException e) {
            throw new JobFailureException(Category.GIT_ERROR, "Could not read repository storage.", e);
        }
    }

    private Repository openLocal(SourceRepository repo) throws IOException {
        // Re-validate: the filesystem may have changed since registration.
        SourceValidator.Validated v = validator.validate(SourceType.LOCAL, repo.location());
        Path gitDir = SourceValidator.gitDirOf(Path.of(v.location()));
        if (gitDir == null) {
            throw new JobFailureException(Category.INVALID_SOURCE, "Local path is not a Git repository.");
        }
        return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
    }

    private Repository openRemote(SourceRepository repo, Collection<String> requiredCommits) throws IOException {
        Path dir = workspaceFor(repo.id());
        if (!requiredCommits.isEmpty() && hasAll(dir, requiredCommits)) {
            return openBare(dir);
        }
        SourceValidator.Validated v = validator.validate(SourceType.GIT_URL, repo.location());
        Files.createDirectories(dir.getParent());
        if (!Files.isDirectory(dir)) log.info("cloning repository {} from host {}", repo.id(), URI.create(v.location()).getHost());
        transfer(v.location(), dir);
        return openBare(dir);
    }

    /**
     * Clones {@code url} into the bare workspace {@code dir}, or fetches its default branch when the workspace exists.
     * Redirects are refused and the size and time limits are enforced while the transfer runs; on failure the workspace
     * is deleted.
     */
    void transfer(String url, Path dir) throws IOException {
        LimitMonitor monitor = new LimitMonitor(dir, props.maxRepoBytes(), Duration.ofSeconds(props.cloneTimeoutSeconds()));
        try {
            boolean fresh = !Files.isDirectory(dir);
            if (fresh) {
                Git.init().setBare(true).setDirectory(dir.toFile()).call().close();
            }
            try (Repository bare = openBare(dir); Git git = new Git(bare)) {
                StoredConfig config = bare.getConfig();
                config.setString("http", null, "followRedirects", "false");
                config.setString("remote", "origin", "url", url);
                config.save();
                String branch = fresh ? defaultBranch(git) : bare.getBranch();
                String spec = "+refs/heads/" + branch + ":refs/heads/" + branch;
                git.fetch().setRemote("origin").setRefSpecs(spec).setDepth(props.cloneDepth())
                        .setTimeout(props.cloneTimeoutSeconds()).setProgressMonitor(monitor).call();
                if (fresh) {
                    RefUpdate head = bare.updateRef(Constants.HEAD);
                    head.disableRefLog();
                    head.link("refs/heads/" + branch);
                }
            }
        } catch (GitAPIException | RuntimeException e) {
            deleteQuietly(dir);
            if (e instanceof JobFailureException j) throw j;
            throw monitor.limitFailure().orElseGet(() -> classify(e));
        }
        if (directoryBytes(dir) > props.maxRepoBytes()) {
            deleteQuietly(dir);
            throw sizeExceeded(props.maxRepoBytes());
        }
    }

    /** The branch the remote's HEAD points to, from the advertised refs. */
    private static String defaultBranch(Git git) throws GitAPIException {
        Ref head = git.lsRemote().setRemote("origin").callAsMap().get(Constants.HEAD);
        if (head == null) {
            throw new JobFailureException(Category.REPOSITORY_UNAVAILABLE, "The repository has no default branch.");
        }
        String target = head.isSymbolic() ? head.getTarget().getName() : null;
        if (target == null || !target.startsWith("refs/heads/")) {
            throw new JobFailureException(Category.REPOSITORY_UNAVAILABLE, "The repository's default branch is not advertised.");
        }
        return target.substring("refs/heads/".length());
    }

    private static boolean hasAll(Path dir, Collection<String> commits) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (Repository bare = openBare(dir)) {
            for (String c : commits) {
                if (!ObjectId.isId(c) || !bare.getObjectDatabase().has(ObjectId.fromString(c))) return false;
            }
            return true;
        }
    }

    private static Repository openBare(Path dir) throws IOException {
        return new FileRepositoryBuilder().setGitDir(dir.toFile()).setMustExist(true).build();
    }

    /** A plain failure for a transfer error, judged from the messages along its cause chain. */
    private static JobFailureException classify(Exception e) {
        StringBuilder all = new StringBuilder();
        boolean transport = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            all.append(c.getMessage()).append(" | ");
            transport |= c instanceof TransportException || c instanceof org.eclipse.jgit.api.errors.TransportException;
        }
        String m = all.toString().toLowerCase();
        if (m.contains("redirect")) {
            return new JobFailureException(Category.INVALID_SOURCE,
                    "The Git host answered with a redirect; redirects are not followed. Register the final URL instead.", e);
        }
        if (m.contains("not found") || m.contains("authentication is required") || m.contains("not authorized")
                || m.contains("401") || m.contains("403") || m.contains("404")) {
            return new JobFailureException(Category.REPOSITORY_UNAVAILABLE,
                    "Repository not found or not public. Only public repositories are supported.", e);
        }
        return transport ? new JobFailureException(Category.GIT_ERROR, "Network error while contacting the Git host.", e)
                : new JobFailureException(Category.GIT_ERROR, "Git clone/fetch failed.", e);
    }

    private static JobFailureException sizeExceeded(long limit) {
        return new JobFailureException(Category.LIMIT_EXCEEDED, "Repository exceeds the size limit of " + limit + " bytes.");
    }

    private Path workspaceFor(UUID repositoryId) {
        return Path.of(props.workspaceDir()).toAbsolutePath().normalize().resolve(repositoryId + ".git");
    }

    private static long directoryBytes(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort; a leftover directory is overwritten or cleaned on the next attempt
        }
    }

    /** Cancels a transfer once the time limit passes or the workspace grows beyond the size limit. */
    static final class LimitMonitor implements ProgressMonitor {
        private final Path dir;
        private final long maxBytes;
        private final long deadline;
        private long nextSizeCheck;
        private volatile JobFailureException failure;

        LimitMonitor(Path dir, long maxBytes, Duration timeout) {
            this.dir = dir;
            this.maxBytes = maxBytes;
            this.deadline = System.nanoTime() + timeout.toNanos();
        }

        java.util.Optional<JobFailureException> limitFailure() {
            if (failure == null && System.nanoTime() > deadline) {
                failure = new JobFailureException(Category.TIMEOUT, "Clone exceeded the time limit.");
            }
            return java.util.Optional.ofNullable(failure);
        }

        @Override
        public boolean isCancelled() {
            if (failure != null) return true;
            long now = System.nanoTime();
            if (now > deadline) {
                failure = new JobFailureException(Category.TIMEOUT, "Clone exceeded the time limit.");
                return true;
            }
            if (now >= nextSizeCheck) {
                nextSizeCheck = now + SIZE_CHECK_NANOS;
                try {
                    if (directoryBytes(dir) > maxBytes) {
                        failure = sizeExceeded(maxBytes);
                        return true;
                    }
                } catch (IOException | java.io.UncheckedIOException ignored) {
                    // files appear and disappear during a transfer; the next check or the final one decides
                }
            }
            return false;
        }

        @Override
        public void start(int totalTasks) {
        }

        @Override
        public void beginTask(String title, int totalWork) {
        }

        @Override
        public void update(int completed) {
        }

        @Override
        public void endTask() {
        }

        public void showDuration(boolean enabled) {
        }
    }
}
