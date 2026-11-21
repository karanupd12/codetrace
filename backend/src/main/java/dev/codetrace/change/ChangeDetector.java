package dev.codetrace.change;

import dev.codetrace.change.ChangeSet.ChangeType;
import dev.codetrace.change.ChangeSet.FileChange;
import dev.codetrace.change.ChangeSet.Hunk;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.springframework.stereotype.Component;

/**
 * Computes the line-level difference between two commits by comparing their trees directly. Nothing is checked out and
 * no file is executed; unusually large blobs are treated as binary so they are never diffed.
 */
@Component
public class ChangeDetector {
    private final ImpactProperties limits;
    private final IngestionProperties ingestion;

    public ChangeDetector(ImpactProperties limits, IngestionProperties ingestion) {
        this.limits = limits;
        this.ingestion = ingestion;
    }

    public ChangeSet detect(Repository repo, String baseCommit, String headCommit) {
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter diff = new DiffFormatter(NullOutputStream.INSTANCE)) {
            CanonicalTreeParser base = tree(repo, walk, baseCommit);
            CanonicalTreeParser head = tree(repo, walk, headCommit);
            diff.setRepository(repo);
            diff.setDetectRenames(true);
            diff.setBinaryFileThreshold((int) Math.min(Integer.MAX_VALUE, ingestion.maxFileBytes()));
            List<DiffEntry> entries = diff.scan(base, head);
            if (entries.size() > limits.maxChangedFiles()) {
                throw new IngestionException(Category.LIMIT_EXCEEDED, "The change touches " + entries.size()
                        + " files; the limit is " + limits.maxChangedFiles() + ".");
            }
            List<FileChange> files = new ArrayList<>();
            for (DiffEntry e : entries) {
                files.add(fileChange(diff, e));
            }
            files.sort(java.util.Comparator.comparing(FileChange::path));
            return new ChangeSet(baseCommit, headCommit, List.copyOf(files));
        } catch (IOException e) {
            throw new IngestionException(Category.GIT_ERROR, "Could not compare the two revisions.", e);
        }
    }

    private FileChange fileChange(DiffFormatter diff, DiffEntry e) throws IOException {
        ChangeType type = switch (e.getChangeType()) {
            case ADD -> ChangeType.ADDED;
            case DELETE -> ChangeType.DELETED;
            case RENAME, COPY -> ChangeType.RENAMED;
            case MODIFY -> ChangeType.MODIFIED;
        };
        String oldPath = e.getChangeType() == DiffEntry.ChangeType.ADD ? null : e.getOldPath();
        String newPath = e.getChangeType() == DiffEntry.ChangeType.DELETE ? null : e.getNewPath();
        var header = diff.toFileHeader(e);
        boolean binary = header.getPatchType() == org.eclipse.jgit.patch.FileHeader.PatchType.BINARY;
        List<Hunk> hunks = new ArrayList<>();
        if (!binary) {
            for (Edit edit : header.toEditList()) {
                hunks.add(new Hunk(edit.getBeginA() + 1, edit.getEndA(), edit.getBeginB() + 1, edit.getEndB()));
            }
        }
        return new FileChange(type, oldPath, newPath, capHunks(hunks), binary);
    }

    /** Keeps the first hunks and folds the rest into one so a pathological file stays bounded but fully covered. */
    private List<Hunk> capHunks(List<Hunk> hunks) {
        int max = limits.maxHunksPerFile();
        if (hunks.size() <= max) return List.copyOf(hunks);
        List<Hunk> out = new ArrayList<>(hunks.subList(0, max - 1));
        List<Hunk> rest = hunks.subList(max - 1, hunks.size());
        Hunk first = rest.get(0);
        Hunk last = rest.get(rest.size() - 1);
        out.add(new Hunk(first.oldStart(), Math.max(first.oldStart() - 1, last.oldEnd()),
                first.newStart(), Math.max(first.newStart() - 1, last.newEnd())));
        return List.copyOf(out);
    }

    private static CanonicalTreeParser tree(Repository repo, RevWalk walk, String commit) throws IOException {
        String shortName = commit.substring(0, Math.min(7, commit.length()));
        RevCommit c;
        try {
            ObjectId id = repo.resolve(commit + "^{commit}");
            if (id == null) {
                throw new org.eclipse.jgit.errors.MissingObjectException(ObjectId.zeroId(), "commit");
            }
            c = walk.parseCommit(id);
        } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
            throw new IngestionException(Category.REVISION_NOT_FOUND, "Revision " + shortName + " is not available in the repository.");
        }
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repo.newObjectReader()) {
            parser.reset(reader, c.getTree());
        }
        return parser;
    }
}
