package dev.codetrace.impact.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.impact.change.ChangeSet.ChangeType;
import dev.codetrace.impact.change.ChangeSet.FileChange;
import dev.codetrace.impact.change.ChangeSet.Hunk;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeDetectorTest {
    @TempDir
    Path tmp;

    private static String lines(int n, String prefix) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> prefix + i).reduce("", (a, b) -> a + b + "\n");
    }

    private static Map<String, Entry> files(Object... kv) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], Entry.file((String) kv[i + 1]));
        return m;
    }

    private ChangeDetector detector(int maxFiles, int maxHunks) {
        return new ChangeDetector(new ImpactProperties(maxFiles, maxHunks, 4, 8, 5000, 5),
                GitFixtures.props(tmp.resolve("ws"), List.of()));
    }

    private Repository open() throws Exception {
        return new FileRepositoryBuilder().setGitDir(tmp.resolve(".git").toFile()).setMustExist(true).build();
    }

    private FileChange only(ChangeSet cs, String path) {
        return cs.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void reportsModifiedLinesOnBothSides() throws Exception {
        String base = GitFixtures.createRepo(tmp, files("A.java", lines(10, "line")));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo,
                    files("A.java", lines(10, "line").replace("line4\n", "changed4\nextra\n")), base, "edit");

            ChangeSet cs = detector(100, 100).detect(repo, base, head);

            FileChange f = only(cs, "A.java");
            assertThat(f.type()).isEqualTo(ChangeType.MODIFIED);
            // one edit, reported per side: line 4 removed from the base, lines 4-5 added in the head
            assertThat(f.hunks()).containsExactly(new Hunk(4, 4, 4, 3), new Hunk(4, 3, 4, 5));
            assertThat(f.hunks().get(0).touchesOld(4, 4)).isTrue();
            assertThat(f.hunks().get(1).touchesNew(5, 5)).isTrue();
            assertThat(f.hunks()).noneMatch(h -> h.touchesNew(6, 9));
            assertThat(f.ignoredHunks()).isZero();
        }
    }

    @Test
    void pureInsertionHasAnEmptyOldSide() throws Exception {
        String base = GitFixtures.createRepo(tmp, files("A.java", lines(5, "l")));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("A.java", lines(5, "l").replace("l3\n", "l3\nnew\n")), base, "insert");

            Hunk h = only(detector(100, 100).detect(repo, base, head), "A.java").hunks().get(0);

            assertThat(h.newStart()).isEqualTo(4);
            assertThat(h.newEnd()).isEqualTo(4);
            assertThat(h.oldEnd()).isLessThan(h.oldStart());
            assertThat(h.touchesOld(1, 100)).isFalse(); // nothing was removed
        }
    }

    @Test
    void editsToCommentsAndBlankLinesAreIgnored() throws Exception {
        String before = """
                class A {
                    int x;

                    void m() {
                        x++;
                    }
                }
                """;
        String after = """
                class A {
                    /** The counter. */
                    int x;

                    // increments x
                    void m() {
                        x++; // still the same code
                    }
                }
                """;
        String base = GitFixtures.createRepo(tmp, files("A.java", before));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("A.java", after), base, "docs only");

            FileChange f = only(detector(100, 100).detect(repo, base, head), "A.java");

            // the trailing comment changed a code line, so that line counts; the Javadoc and the comment line do not
            assertThat(f.hunks()).allSatisfy(h -> assertThat(h.touchesNew(2, 2) || h.touchesNew(5, 5)).isFalse());
            assertThat(f.hunks()).anyMatch(h -> h.touchesNew(7, 7));
            assertThat(f.ignoredHunks()).isEqualTo(2);
        }
    }

    @Test
    void addingAMemberWithItsJavadocOnlyCoversTheMembersCode() throws Exception {
        String before = "class A {\n    void a() {}\n}\n";
        String after = "class A {\n    void a() {}\n\n    /**\n     * New.\n     */\n    void b() {}\n}\n";
        String base = GitFixtures.createRepo(tmp, files("A.java", before));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("A.java", after), base, "add b");

            FileChange f = only(detector(100, 100).detect(repo, base, head), "A.java");

            assertThat(f.hunks()).containsExactly(new Hunk(3, 2, 7, 7)); // only "void b() {}"
        }
    }

    @Test
    void reportsAddedDeletedAndRenamedFiles() throws Exception {
        String content = lines(20, "same");
        String base = GitFixtures.createRepo(tmp, files("Old.java", content, "Gone.java", lines(3, "gone")));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("New.java", content, "Fresh.java", lines(4, "fresh")), base, "mix");

            ChangeSet cs = detector(100, 100).detect(repo, base, head);

            assertThat(only(cs, "Fresh.java").type()).isEqualTo(ChangeType.ADDED);
            assertThat(only(cs, "Fresh.java").oldPath()).isNull();
            assertThat(only(cs, "Gone.java").type()).isEqualTo(ChangeType.DELETED);
            assertThat(only(cs, "Gone.java").newPath()).isNull();
            FileChange renamed = only(cs, "New.java");
            assertThat(renamed.type()).isEqualTo(ChangeType.RENAMED);
            assertThat(renamed.oldPath()).isEqualTo("Old.java");
            assertThat(renamed.hunks()).isEmpty(); // identical content: nothing changed inside
        }
    }

    @Test
    void identicalCommitsHaveNoChanges() throws Exception {
        String base = GitFixtures.createRepo(tmp, files("A.java", "x\n"));
        try (Repository repo = open()) {
            assertThat(detector(100, 100).detect(repo, base, base).files()).isEmpty();
        }
    }

    @Test
    void separatesJavaFromOtherFilesAndTreatsBinaryAsOpaque() throws Exception {
        Map<String, Entry> baseFiles = files("A.java", "a\n", "README.md", "r\n");
        baseFiles.put("logo.bin", new Entry(org.eclipse.jgit.lib.FileMode.REGULAR_FILE, new byte[] {0, 1, 2, 3, 0}));
        String base = GitFixtures.createRepo(tmp, baseFiles);
        try (Repository repo = open()) {
            Map<String, Entry> headFiles = files("A.java", "b\n", "README.md", "r2\n");
            headFiles.put("logo.bin", new Entry(org.eclipse.jgit.lib.FileMode.REGULAR_FILE, new byte[] {0, 9, 9, 9, 0}));
            String head = GitFixtures.commit(repo, headFiles, base, "edit all");

            ChangeSet cs = detector(100, 100).detect(repo, base, head);

            assertThat(cs.javaFiles()).extracting(FileChange::path).containsExactly("A.java");
            assertThat(cs.nonJavaFileCount()).isEqualTo(2);
            assertThat(only(cs, "logo.bin").binary()).isTrue();
            assertThat(only(cs, "logo.bin").hunks()).isEmpty();
        }
    }

    @Test
    void oversizedBlobsAreNeverDiffed() throws Exception {
        String base = GitFixtures.createRepo(tmp, files("Big.java", lines(2000, "padding padding padding ")));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("Big.java", lines(2000, "padding padding padding ") + "tail\n"), base, "grow");
            ChangeDetector small = new ChangeDetector(new ImpactProperties(100, 100, 4, 8, 5000, 5),
                    GitFixtures.limited(5000, 1024, 1L << 20, 1000)); // 1 KiB per-file limit

            assertThat(only(small.detect(repo, base, head), "Big.java").binary()).isTrue();
        }
    }

    @Test
    void foldsExcessHunksIntoOneCoveringTheRemainder() throws Exception {
        String before = lines(100, "l");
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 100; i++) after.append(i % 2 == 0 ? "changed" + i : "l" + i).append('\n'); // 50 separate edits
        String base = GitFixtures.createRepo(tmp, files("A.java", before));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("A.java", after.toString()), base, "many edits");

            List<Hunk> hunks = only(detector(100, 5).detect(repo, base, head), "A.java").hunks();

            assertThat(hunks).hasSize(5);
            Hunk last = hunks.get(4);
            assertThat(last.newEnd()).isEqualTo(100); // the folded hunk still covers the tail of the file
            assertThat(last.touchesNew(100, 100)).isTrue();
        }
    }

    @Test
    void rejectsChangesLargerThanTheLimit() throws Exception {
        String base = GitFixtures.createRepo(tmp, files("A.java", "a\n"));
        try (Repository repo = open()) {
            String head = GitFixtures.commit(repo, files("A.java", "b\n", "B.java", "b\n", "C.java", "c\n"), base, "wide");

            assertThatThrownBy(() -> detector(2, 100).detect(repo, base, head))
                    .isInstanceOfSatisfying(JobFailureException.class, e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
        }
    }

    @Test
    void unknownRevisionFailsPermanently() throws Exception {
        String base = GitFixtures.createRepo(tmp, files("A.java", "a\n"));
        try (Repository repo = open()) {
            String missing = "0123456789abcdef0123456789abcdef01234567";

            assertThatThrownBy(() -> detector(100, 100).detect(repo, base, missing))
                    .isInstanceOfSatisfying(JobFailureException.class, e -> assertThat(e.category()).isEqualTo(Category.REVISION_NOT_FOUND));
        }
    }
}
