package dev.codetrace.ingestion.indexer;

import dev.codetrace.jobs.JobFailureException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.FileExtraction;
import java.time.Duration;
import java.util.function.Function;
import dev.codetrace.ingestion.model.ReusableIndex;
import dev.codetrace.ingestion.model.ReusableIndex.ReusableFile;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaIndexerTest {
    @TempDir
    Path tmp;

    private Repository open(Path dir) throws Exception {
        return new FileRepositoryBuilder().setGitDir(dir.resolve(".git").toFile()).setMustExist(true).build();
    }

    @Test
    void indexesDemoRevisionAndHonoursIgnoredPaths() throws Exception {
        String commit = GitFixtures.createDemoRepo(tmp);
        JavaIndexer indexer = new JavaIndexer(GitFixtures.props(tmp.resolve("ws"), List.of()));

        try (Repository repo = open(tmp)) {
            IndexResult r = indexer.index(repo, commit);

            List<String> paths = r.files().stream().map(IndexResult.FileRecord::path).toList();
            assertThat(paths).contains(
                    "src/main/java/com/acme/shop/Order.java",
                    "src/main/java/com/acme/shop/OrderService.java",
                    "src/main/java/com/acme/shop/Priced.java",
                    "src/main/java/com/acme/shop/Broken.java");
            // ignored trees and non-Java files never reach the parser
            assertThat(paths).noneMatch(p -> p.startsWith("target/") || p.startsWith("node_modules/"));
            assertThat(paths).noneMatch(p -> p.endsWith(".md"));
            assertThat(r.symbols()).noneMatch(s -> s.path().startsWith("target/") || s.path().startsWith("node_modules/"));
            assertThat(r.symbols()).noneMatch(s -> s.symbol().qualifiedName().contains("Generated")
                    || s.symbol().qualifiedName().contains("Vendored"));
            assertThat(r.filesSkipped()).isEqualTo(2); // Generated.java + Vendored.java
            assertThat(r.parserVersion()).startsWith("javaparser-core-");

            // the malformed file is reported, not fatal, and the good files still produced symbols
            assertThat(r.diagnostics()).anySatisfy(d -> {
                assertThat(d.path()).endsWith("Broken.java");
                assertThat(d.diagnostic().code()).isEqualTo("SYNTAX_ERROR");
            });
            assertThat(r.symbols()).anyMatch(s -> s.symbol().qualifiedName().equals("com.acme.shop.Order#addLine(String)"));
        }
    }

    @Test
    void sameRevisionAlwaysProducesTheSameResult() throws Exception {
        String commit = GitFixtures.createDemoRepo(tmp);
        JavaIndexer indexer = new JavaIndexer(GitFixtures.props(tmp.resolve("ws"), List.of()));

        try (Repository repo = open(tmp)) {
            IndexResult a = indexer.index(repo, commit);
            IndexResult b = indexer.index(repo, commit);

            assertThat(b.symbols()).isEqualTo(a.symbols());
            assertThat(b.files()).isEqualTo(a.files());
            assertThat(b.diagnostics()).isEqualTo(a.diagnostics());
        }
    }

    @Test
    void skipsSymlinksAndSubmodulesWithoutFollowingThem() throws Exception {
        Map<String, Entry> files = new LinkedHashMap<>();
        files.put("Real.java", Entry.file("class Real {}"));
        files.put("Link.java", Entry.symlink("/etc/passwd"));
        String commit = GitFixtures.createRepo(tmp, files);
        JavaIndexer indexer = new JavaIndexer(GitFixtures.props(tmp.resolve("ws"), List.of()));

        try (Repository repo = open(tmp)) {
            IndexResult r = indexer.index(repo, commit);

            assertThat(r.files()).anySatisfy(f -> {
                assertThat(f.path()).isEqualTo("Link.java");
                assertThat(f.status()).isEqualTo("SKIPPED");
                assertThat(f.skipReason()).isEqualTo("SYMLINK");
            });
            assertThat(r.symbols()).extracting(s -> s.symbol().qualifiedName()).containsExactly("Real");
        }
    }

    @Test
    void oversizedFilesAreSkippedWithAWarning() throws Exception {
        Map<String, Entry> files = new LinkedHashMap<>();
        files.put("Small.java", Entry.file("class Small {}"));
        files.put("Big.java", Entry.file("class Big { /*" + "x".repeat(2000) + "*/ }"));
        String commit = GitFixtures.createRepo(tmp, files);
        JavaIndexer indexer = new JavaIndexer(GitFixtures.limited(100, 500, 1_000_000, 1000));

        try (Repository repo = open(tmp)) {
            IndexResult r = indexer.index(repo, commit);

            assertThat(r.files()).anySatisfy(f -> {
                assertThat(f.path()).isEqualTo("Big.java");
                assertThat(f.skipReason()).isEqualTo("TOO_LARGE");
            });
            assertThat(r.diagnostics()).anyMatch(d -> d.diagnostic().code().equals("FILE_TOO_LARGE"));
            assertThat(r.symbols()).extracting(s -> s.symbol().qualifiedName()).containsExactly("Small");
        }
    }

    @Test
    void tooManyFilesFailsTheJobWithLimitExceeded() throws Exception {
        Map<String, Entry> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) files.put("F" + i + ".java", Entry.file("class F" + i + " {}"));
        String commit = GitFixtures.createRepo(tmp, files);
        JavaIndexer indexer = new JavaIndexer(GitFixtures.limited(4, 100_000, 1_000_000, 1000));

        try (Repository repo = open(tmp)) {
            assertThatThrownBy(() -> indexer.index(repo, commit))
                    .isInstanceOfSatisfying(JobFailureException.class,
                            e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
        }
    }

    @Test
    void totalByteAndSymbolLimitsAreEnforced() throws Exception {
        Map<String, Entry> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) files.put("F" + i + ".java", Entry.file("class F" + i + " { int a; int b; }"));
        String commit = GitFixtures.createRepo(tmp, files);

        try (Repository repo = open(tmp)) {
            assertThatThrownBy(() -> new JavaIndexer(GitFixtures.limited(100, 100_000, 60, 1000)).index(repo, commit))
                    .isInstanceOfSatisfying(JobFailureException.class,
                            e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
            assertThatThrownBy(() -> new JavaIndexer(GitFixtures.limited(100, 100_000, 1_000_000, 5)).index(repo, commit))
                    .isInstanceOfSatisfying(JobFailureException.class,
                            e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
        }
    }

    @Test
    void unknownRevisionIsAPermanentFailure() throws Exception {
        GitFixtures.createDemoRepo(tmp);
        JavaIndexer indexer = new JavaIndexer(GitFixtures.props(tmp.resolve("ws"), List.of()));

        try (Repository repo = open(tmp)) {
            assertThatThrownBy(() -> indexer.index(repo, "0123456789012345678901234567890123456789"))
                    .isInstanceOfSatisfying(JobFailureException.class, e -> {
                        assertThat(e.category()).isEqualTo(Category.REVISION_NOT_FOUND);
                        assertThat(e.category().retryable()).isFalse();
                    });
        }
    }

    @Test
    void indexesTheRequestedCommitNotJustHead() throws Exception {
        String first = GitFixtures.createRepo(tmp, Map.of("A.java", Entry.file("class A {}")));
        try (Repository repo = open(tmp)) {
            GitFixtures.commit(repo, Map.of("A.java", Entry.file("class A {}"), "B.java", Entry.file("class B {}")),
                    first, "second");
            JavaIndexer indexer = new JavaIndexer(GitFixtures.props(tmp.resolve("ws"), List.of()));

            IndexResult atFirst = indexer.index(repo, first);

            assertThat(atFirst.symbols()).extracting(s -> s.symbol().qualifiedName()).containsExactly("A");
        }
    }

    @Test
    void unchangedAndRenamedFilesAreReusedWhileChangedAndNewFilesAreParsed() throws Exception {
        String first = GitFixtures.createRepo(tmp, Map.of(
                "p/Same.java", Entry.file("package p; class Same { void a() {} }"),
                "p/Moved.java", Entry.file("package p; class Moved { void b() {} }"),
                "p/Edited.java", Entry.file("package p; class Edited { void c() {} }"),
                "p/Gone.java", Entry.file("package p; class Gone {}")));
        try (Repository repo = open(tmp)) {
            String second = GitFixtures.commit(repo, Map.of(
                    "p/Same.java", Entry.file("package p; class Same { void a() {} }"),
                    "q/Moved.java", Entry.file("package p; class Moved { void b() {} }"),
                    "p/Edited.java", Entry.file("package p; class Edited { void c() {} void d() {} }"),
                    "p/Added.java", Entry.file("package p; class Added {}")), first, "second");
            JavaIndexer indexer = new JavaIndexer(GitFixtures.props(tmp.resolve("ws"), List.of()));
            IndexResult base = indexer.index(repo, first);
            UUID baseJob = UUID.randomUUID();
            Map<String, ReusableFile> byBlob = new HashMap<>();
            for (IndexResult.FileRecord f : base.files()) {
                int symbols = (int) base.symbols().stream().filter(s -> s.path().equals(f.path())).count();
                byBlob.put(f.blobId(), new ReusableFile(f.path(), f.packageName(), f.status(), symbols, 0));
            }

            IndexResult incremental = indexer.index(repo, second, new ReusableIndex(baseJob, byBlob));
            IndexResult full = indexer.index(repo, second);

            assertThat(incremental.baseJobId()).isEqualTo(baseJob);
            assertThat(incremental.reused()).containsExactlyInAnyOrder(
                    new IndexResult.ReusedFile("p/Same.java", "p/Same.java"),
                    new IndexResult.ReusedFile("q/Moved.java", "p/Moved.java"));
            assertThat(incremental.symbols()).extracting(IndexResult.SymbolRecord::path).containsOnly(
                    "p/Edited.java", "p/Added.java");
            assertThat(incremental.files()).containsExactlyInAnyOrderElementsOf(full.files());
            assertThat(incremental.filesParsed()).isEqualTo(full.filesParsed());
            assertThat(full.reused()).isEmpty();
            assertThat(full.baseJobId()).isNull();
        }
    }

    @Test
    void reusedFilesCountTowardsTheSymbolLimit() throws Exception {
        String commit = GitFixtures.createRepo(tmp, Map.of("A.java", Entry.file("class A { void a() {} }")));
        try (Repository repo = open(tmp)) {
            JavaIndexer indexer = new JavaIndexer(GitFixtures.limited(5000, 524_288L, 50_000_000L, 10));
            String blob = indexer.index(repo, commit).files().get(0).blobId();
            ReusableIndex hugeBase = new ReusableIndex(UUID.randomUUID(),
                    Map.of(blob, new ReusableFile("A.java", "", "PARSED", 11, 0)));

            assertThatThrownBy(() -> indexer.index(repo, commit, hugeBase))
                    .isInstanceOfSatisfying(JobFailureException.class,
                            e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
        }
    }

    @Test
    void aFileThatTakesTooLongToParseIsSkippedAndTheRestAreIndexed() throws Exception {
        String commit = GitFixtures.createRepo(tmp, Map.of(
                "p/Slow.java", Entry.file("package p; class Slow {}"),
                "p/Fast.java", Entry.file("package p; class Fast {}")));
        IngestionProperties base = GitFixtures.props(tmp.resolve("ws"), List.of());
        IngestionProperties oneSecond = new IngestionProperties(base.workspaceDir(), base.localRoots(), base.allowedHosts(),
                base.cloneDepth(), base.cloneTimeoutSeconds(), base.maxRepoBytes(), base.maxRevisions(), base.maxJavaFiles(),
                base.maxFileBytes(), base.maxTotalBytes(), base.maxSymbols(), base.maxReferences(), base.parseDeadlineSeconds(),
                base.maxAttempts(), base.staleRunningSeconds(), base.retryDelaySeconds(), base.incremental(),
                base.ignoredDirs(), 1);
        Function<String, FileExtraction> real = new JavaSymbolExtractor()::extract;
        JavaIndexer indexer = new JavaIndexer(oneSecond, () -> source -> {
            if (source.contains("Slow")) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return real.apply(source);
        });

        try (Repository repo = open(tmp)) {
            long start = System.nanoTime();
            IndexResult r = indexer.index(repo, commit);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(r.files()).extracting(f -> f.path() + " " + f.status())
                    .containsExactlyInAnyOrder("p/Slow.java FAILED", "p/Fast.java PARSED");
            assertThat(r.diagnostics()).extracting(d -> d.path() + " " + d.diagnostic().code())
                    .containsExactly("p/Slow.java PARSE_TIMEOUT");
            assertThat(r.symbols()).extracting(s -> s.symbol().qualifiedName()).containsExactly("p.Fast");
        }
    }

    @Test
    void pathSafetyRejectsTraversalAndOddNames() {
        assertThat(JavaIndexer.isSafePath("src/a/B.java")).isTrue();
        assertThat(JavaIndexer.isSafePath("../B.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("a/../B.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("a/./B.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("/abs/B.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("a\\B.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("a//B.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("a/B\0.java")).isFalse();
        assertThat(JavaIndexer.isSafePath("")).isFalse();
    }
}
