package dev.codetrace.impact.change;

import dev.codetrace.common.util.Commits;
import dev.codetrace.impact.change.ChangeSet.ChangeType;
import dev.codetrace.impact.change.ChangeSet.FileChange;
import dev.codetrace.impact.change.ChangeSet.Hunk;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
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
                throw new JobFailureException(Category.LIMIT_EXCEEDED, "The change touches " + entries.size()
                        + " files; the limit is " + limits.maxChangedFiles() + ".");
            }
            List<FileChange> files = new ArrayList<>();
            for (DiffEntry e : entries) {
                files.add(fileChange(repo, diff, e));
            }
            files.sort(java.util.Comparator.comparing(FileChange::path));
            return new ChangeSet(baseCommit, headCommit, List.copyOf(files));
        } catch (IOException e) {
            throw new JobFailureException(Category.GIT_ERROR, "Could not compare the two revisions.", e);
        }
    }

    private FileChange fileChange(Repository repo, DiffFormatter diff, DiffEntry e) throws IOException {
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
        int ignored = 0;
        boolean javaEdit = !binary && (type == ChangeType.MODIFIED || type == ChangeType.RENAMED)
                && newPath != null && newPath.endsWith(".java");
        boolean[] oldCode = javaEdit ? SignificantLines.of(text(repo, e.getOldId().toObjectId())) : null;
        boolean[] newCode = javaEdit ? SignificantLines.of(text(repo, e.getNewId().toObjectId())) : null;
        if (!binary) {
            for (Edit edit : header.toEditList()) {
                if (!javaEdit) {
                    hunks.add(new Hunk(edit.getBeginA() + 1, edit.getEndA(), edit.getBeginB() + 1, edit.getEndB()));
                    continue;
                }
                // keep only runs of lines that hold code; an edit of comments or whitespace changes no behaviour
                List<Hunk> code = new ArrayList<>();
                for (int[] run : runs(oldCode, edit.getBeginA(), edit.getEndA())) {
                    code.add(new Hunk(run[0], run[1], edit.getBeginB() + 1, edit.getBeginB()));
                }
                for (int[] run : runs(newCode, edit.getBeginB(), edit.getEndB())) {
                    code.add(new Hunk(edit.getBeginA() + 1, edit.getBeginA(), run[0], run[1]));
                }
                if (code.isEmpty()) ignored++;
                hunks.addAll(code);
            }
        }
        return new FileChange(type, oldPath, newPath, capHunks(hunks), binary, ignored);
    }

    /** 1-based inclusive runs of consecutive significant lines within the 0-based half-open range [from, to). */
    private static List<int[]> runs(boolean[] significant, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = from; i < to; i++) {
            boolean code = i >= significant.length || significant[i]; // unknown lines count as code
            if (code && start < 0) start = i;
            if (!code && start >= 0) {
                out.add(new int[] {start + 1, i});
                start = -1;
            }
        }
        if (start >= 0) out.add(new int[] {start + 1, to});
        return out;
    }

    private static String text(Repository repo, ObjectId blob) throws IOException {
        return new String(repo.open(blob, org.eclipse.jgit.lib.Constants.OBJ_BLOB).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
        String shortName = Commits.shortHash(commit);
        RevCommit c;
        try {
            ObjectId id = repo.resolve(commit + "^{commit}");
            if (id == null) {
                throw new org.eclipse.jgit.errors.MissingObjectException(ObjectId.zeroId(), "commit");
            }
            c = walk.parseCommit(id);
        } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
            throw new JobFailureException(Category.REVISION_NOT_FOUND, "Revision " + shortName + " is not available in the repository.");
        }
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repo.newObjectReader()) {
            parser.reset(reader, c.getTree());
        }
        return parser;
    }
}
