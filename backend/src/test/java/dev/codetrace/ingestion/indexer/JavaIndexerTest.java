package dev.codetrace.ingestion.indexer;

import dev.codetrace.jobs.JobFailureException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import dev.codetrace.ingestion.model.IndexResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
