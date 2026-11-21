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
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Opens repositories for reading. Remote repositories are fetched into a bare, depth-limited workspace; local ones are
 * opened in place, read-only. Nothing is ever checked out, so no working-tree files, hooks, filters or symlinks exist
 * to be triggered, and nothing from the repository is executed.
 */
@Component
public class GitSourceManager {
    private static final Logger log = LoggerFactory.getLogger(GitSourceManager.class);

    private final IngestionProperties props;
    private final SourceValidator validator;

    public GitSourceManager(IngestionProperties props, SourceValidator validator) {
        this.props = props;
        this.validator = validator;
    }

    /** Opens (cloning/fetching first when remote). The caller must close the returned repository. */
    public Repository open(SourceRepository repo) {
        try {
            return repo.sourceType() == SourceType.GIT_URL ? openRemote(repo) : openLocal(repo);
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

    private Repository openRemote(SourceRepository repo) throws IOException {
        SourceValidator.Validated v = validator.validate(SourceType.GIT_URL, repo.location());
        String url = v.location();
        Path dir = workspaceFor(repo.id());
        Files.createDirectories(dir.getParent());
        long deadline = System.nanoTime() + Duration.ofSeconds(props.cloneTimeoutSeconds()).toNanos();
        ProgressMonitor monitor = new ProgressMonitor() {
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

            @Override
            public boolean isCancelled() {
                return System.nanoTime() > deadline;
            }

            public void showDuration(boolean enabled) {
            }
        };
        try {
            if (Files.isDirectory(dir)) {
                try (Repository existing = new FileRepositoryBuilder().setGitDir(dir.toFile()).setMustExist(true).build();
                     Git git = new Git(existing)) {
                    String branch = existing.getBranch();
                    String spec = "+refs/heads/" + branch + ":refs/heads/" + branch;
                    git.fetch().setRemote("origin").setRefSpecs(spec).setDepth(props.cloneDepth())
                            .setTimeout(props.cloneTimeoutSeconds()).setProgressMonitor(monitor).call();
                }
            } else {
                log.info("cloning repository {} from host {}", repo.id(), URI.create(url).getHost());
                try (Git git = Git.cloneRepository().setURI(url).setDirectory(dir.toFile()).setBare(true)
                        .setDepth(props.cloneDepth()).setCloneAllBranches(false).setCloneSubmodules(false)
                        .setTimeout(props.cloneTimeoutSeconds()).setProgressMonitor(monitor).call()) {
                    // closed immediately; reopened below as a plain Repository
                }
            }
        } catch (TransportException e) {
            deleteQuietly(dir);
            throw classifyTransport(e);
        } catch (GitAPIException | RuntimeException e) {
            deleteQuietly(dir);
            if (System.nanoTime() > deadline) {
                throw new JobFailureException(Category.TIMEOUT, "Clone exceeded the time limit.", e);
            }
            throw new JobFailureException(Category.GIT_ERROR, "Git clone/fetch failed.", e);
        }
        if (directoryBytes(dir) > props.maxRepoBytes()) {
            deleteQuietly(dir);
            throw new JobFailureException(Category.LIMIT_EXCEEDED,
                    "Repository exceeds the size limit of " + props.maxRepoBytes() + " bytes.");
        }
        return new FileRepositoryBuilder().setGitDir(dir.toFile()).setMustExist(true).build();
    }

    private static JobFailureException classifyTransport(TransportException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase();
        if (m.contains("not found") || m.contains("authentication is required") || m.contains("not authorized")
                || m.contains("401") || m.contains("403") || m.contains("404")) {
            return new JobFailureException(Category.REPOSITORY_UNAVAILABLE,
                    "Repository not found or not public. Only public repositories are supported.", e);
        }
        return new JobFailureException(Category.GIT_ERROR, "Network error while contacting the Git host.", e);
    }

    private Path workspaceFor(UUID repositoryId) {
        return Path.of(props.workspaceDir()).toAbsolutePath().normalize().resolve(repositoryId + ".git");
    }

    private static long directoryBytes(Path dir) throws IOException {
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
}
