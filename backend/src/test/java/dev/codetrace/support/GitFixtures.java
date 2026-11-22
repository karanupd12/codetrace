package dev.codetrace.support;

import dev.codetrace.config.IngestionProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

/**
 * Builds throwaway Git repositories for tests. Commits are written straight to the object database (no working
 * tree, no user git config, no signing), so results do not depend on the developer's machine.
 */
public final class GitFixtures {
    public record Entry(FileMode mode, byte[] content) {
        public static Entry file(String text) {
            return new Entry(FileMode.REGULAR_FILE, text.getBytes(StandardCharsets.UTF_8));
        }

        public static Entry symlink(String target) {
            return new Entry(FileMode.SYMLINK, target.getBytes(StandardCharsets.UTF_8));
        }
    }

    private GitFixtures() {
    }

    /** Creates a non-bare repo at {@code dir} containing the demo-java fixture tree; returns the commit id. */
    public static String createDemoRepo(Path dir) throws IOException, GitAPIException {
        Map<String, Entry> files = new LinkedHashMap<>();
        Path root = fixtureRoot("demo-java");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                files.put(rel, new Entry(FileMode.REGULAR_FILE, Files.readAllBytes(p)));
            }
        }
        return createRepo(dir, files);
    }

    public static String createRepo(Path dir, Map<String, Entry> files) throws IOException, GitAPIException {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            return commit(git.getRepository(), files, null, "initial");
        }
    }

    /** Writes a commit with exactly these files; moves refs/heads/main to it. */
    public static String commit(Repository repo, Map<String, Entry> files, String parent, String message)
            throws IOException {
        try (ObjectInserter ins = repo.newObjectInserter()) {
            DirCache dc = DirCache.newInCore();
            DirCacheBuilder b = dc.builder();
            for (Map.Entry<String, Entry> e : files.entrySet()) {
                DirCacheEntry ent = new DirCacheEntry(e.getKey());
                ent.setFileMode(e.getValue().mode());
                ent.setObjectId(ins.insert(Constants.OBJ_BLOB, e.getValue().content()));
                b.add(ent);
            }
            b.finish();
            ObjectId tree = dc.writeTree(ins);
            PersonIdent who = new PersonIdent("Test", "test@example.com");
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(tree);
            cb.setAuthor(who);
            cb.setCommitter(who);
            cb.setMessage(message);
            if (parent != null) cb.setParentId(ObjectId.fromString(parent));
            ObjectId commit = ins.insert(cb);
            ins.flush();
            RefUpdate ru = repo.updateRef("refs/heads/main");
            ru.setNewObjectId(commit);
            ru.setForceUpdate(true);
            ru.update();
            return commit.getName();
        }
    }

    public static Path fixtureRoot(String name) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve("test-fixtures").resolve(name);
            if (Files.isDirectory(candidate)) return candidate;
            p = p.getParent();
        }
        throw new IllegalStateException("test-fixtures/" + name + " not found above " + Path.of("").toAbsolutePath());
    }

    public static IngestionProperties props(Path workspace, List<String> localRoots) {
        return new IngestionProperties(workspace.toString(), localRoots, List.of("github.com", "gitlab.com"), 50, 30,
                200_000_000L, 50, 5000, 524_288L, 50_000_000L, 200_000, 1_000_000, 60, 3, 600, 2, true,
                List.of("target", "build", "out", "node_modules", ".git", ".gradle", ".idea", "dist"), 10);
    }

    /** Same as {@link #props} but with tight limits, for limit tests. */
    public static IngestionProperties limited(int maxJavaFiles, long maxFileBytes, long maxTotalBytes, int maxSymbols) {
        return new IngestionProperties("unused", List.of(), List.of("github.com"), 50, 30, 200_000_000L, 50,
                maxJavaFiles, maxFileBytes, maxTotalBytes, maxSymbols, 1_000_000, 60, 3, 600, 2, true,
                List.of("target", "build", "out", "node_modules", ".git"), 10);
    }
}
