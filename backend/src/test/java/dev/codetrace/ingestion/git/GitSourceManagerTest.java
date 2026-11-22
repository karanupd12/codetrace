package dev.codetrace.ingestion.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.source.service.SourceValidator;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourceManagerTest {
    @TempDir
    Path tmp;

    private static IngestionProperties props(Path workspace, long maxRepoBytes) {
        IngestionProperties p = GitFixtures.props(workspace, List.of());
        return new IngestionProperties(p.workspaceDir(), p.localRoots(), p.allowedHosts(), p.cloneDepth(),
                p.cloneTimeoutSeconds(), maxRepoBytes, p.maxRevisions(), p.maxJavaFiles(), p.maxFileBytes(),
                p.maxTotalBytes(), p.maxSymbols(), p.maxReferences(), p.parseDeadlineSeconds(), p.maxAttempts(),
                p.staleRunningSeconds(), p.retryDelaySeconds(), p.incremental(), p.ignoredDirs());
    }

    private static String fileUrl(Path dir) {
        return dir.resolve(".git").toUri().toString();
    }

    @Test
    void aFreshTransferClonesTheDefaultBranchIntoABareWorkspace() throws Exception {
        String commit = GitFixtures.createRepo(tmp.resolve("origin"), Map.of("A.java", Entry.file("class A {}")));
        Path dir = tmp.resolve("ws").resolve("r.git");
        Files.createDirectories(dir.getParent());
        GitSourceManager git = new GitSourceManager(props(tmp.resolve("ws"), 200_000_000L), mock(SourceValidator.class));

        git.transfer(fileUrl(tmp.resolve("origin")), dir);

        try (Repository r = new org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(dir.toFile()).build()) {
            assertThat(r.isBare()).isTrue();
            assertThat(r.getBranch()).isEqualTo("main");
            assertThat(r.resolve("HEAD")).isEqualTo(ObjectId.fromString(commit));
            assertThat(r.getConfig().getString("http", null, "followRedirects")).isEqualTo("false");
        }
    }

    @Test
    void redirectsAreRefusedAndTheWorkspaceIsRemoved() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere.git/info/refs?service=git-upload-pack");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.start();
        try {
            Path dir = tmp.resolve("ws").resolve("r.git");
            Files.createDirectories(dir.getParent());
            GitSourceManager git = new GitSourceManager(props(tmp.resolve("ws"), 200_000_000L), mock(SourceValidator.class));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git";

            assertThatThrownBy(() -> git.transfer(url, dir)).isInstanceOfSatisfying(JobFailureException.class,
                    e -> assertThat(e.category()).isEqualTo(Category.INVALID_SOURCE));
            assertThat(dir).doesNotExist();
            assertThat(requests.get()).isPositive();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aTransferStopsOnceTheWorkspacePassesTheSizeLimit() throws Exception {
        byte[] noise = new byte[400_000];
        new SecureRandom().nextBytes(noise);
        GitFixtures.createRepo(tmp.resolve("origin"), Map.of("Big.java",
                Entry.file("class Big { String s = \"" + Base64.getEncoder().encodeToString(noise) + "\"; }")));
        Path dir = tmp.resolve("ws").resolve("r.git");
        Files.createDirectories(dir.getParent());
        GitSourceManager git = new GitSourceManager(props(tmp.resolve("ws"), 50_000L), mock(SourceValidator.class));

        assertThatThrownBy(() -> git.transfer(fileUrl(tmp.resolve("origin")), dir))
                .isInstanceOfSatisfying(JobFailureException.class,
                        e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
        assertThat(dir).doesNotExist();
    }

    @Test
    void aWorkspaceHoldingTheNeededCommitsIsNotFetchedAgain() throws Exception {
        Path origin = tmp.resolve("origin");
        String commit = GitFixtures.createRepo(origin, Map.of("A.java", Entry.file("class A {}")));
        SourceValidator validator = mock(SourceValidator.class);
        when(validator.validate(any(), any())).thenReturn(new SourceValidator.Validated(SourceType.GIT_URL, fileUrl(origin), "o/r"));
        GitSourceManager git = new GitSourceManager(props(tmp.resolve("ws"), 200_000_000L), validator);
        SourceRepository repo = new SourceRepository(UUID.randomUUID(), SourceType.GIT_URL, "https://github.com/o/r", "o/r",
                "SYNCED", "main", commit, null);
        git.open(repo).close();

        // The origin disappears: any fetch would now fail.
        when(validator.validate(any(), any())).thenReturn(
                new SourceValidator.Validated(SourceType.GIT_URL, fileUrl(tmp.resolve("gone")), "o/r"));

        try (Repository r = git.open(repo, List.of(commit))) {
            assertThat(r.getObjectDatabase().has(ObjectId.fromString(commit))).isTrue();
        }
        assertThatThrownBy(() -> git.open(repo, List.of("0123456789012345678901234567890123456789")))
                .isInstanceOf(JobFailureException.class);
    }
}
