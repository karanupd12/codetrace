package dev.codetrace.ingestion.indexer;

import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.Diagnostic;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.FileExtraction;
import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.model.IndexResult.DiagnosticRecord;
import dev.codetrace.ingestion.model.IndexResult.FileRecord;
import dev.codetrace.ingestion.model.IndexResult.ImportRecord;
import dev.codetrace.ingestion.model.IndexResult.ReusedFile;
import dev.codetrace.ingestion.model.IndexResult.SymbolRecord;
import dev.codetrace.ingestion.model.ReusableIndex;
import dev.codetrace.ingestion.model.ReusableIndex.ReusableFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

/**
 * Reads Java sources for one pinned commit directly from Git objects (no checkout) and extracts symbols.
 * Enforces file-count, per-file, total-byte, symbol-count and wall-clock limits; a single file that takes longer than
 * {@code fileParseSeconds} to parse is recorded as FAILED with a PARSE_TIMEOUT diagnostic and the job continues. Files whose blob is in the given
 * reusable index are not read or parsed; their facts are copied later and count towards the same limits.
 */
@Component
public class JavaIndexer {
    private final IngestionProperties props;
    private final Supplier<Function<String, FileExtraction>> parsers;

    @Autowired
    public JavaIndexer(IngestionProperties props) {
        this(props, () -> new JavaSymbolExtractor()::extract);
    }

    /** With a custom parser factory; each parser is used by one thread at a time. */
    JavaIndexer(IngestionProperties props, Supplier<Function<String, FileExtraction>> parsers) {
        this.props = props;
        this.parsers = parsers;
    }

    /** The blob ids of the Java files of a commit, read from its tree without loading any content. */
    public List<String> javaBlobIds(Repository repo, String commitHash) {
        List<String> out = new ArrayList<>();
        try (RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            ObjectId id = ObjectId.fromString(commitHash);
            if (!repo.getObjectDatabase().has(id)) return out;
            tw.addTree(rw.parseCommit(id).getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                if (tw.getPathString().endsWith(".java")) out.add(tw.getObjectId(0).name());
            }
        } catch (IOException e) {
            throw new JobFailureException(Category.GIT_ERROR, "Could not read revision contents.", e);
        }
        return out;
    }

    public IndexResult index(Repository repo, String commitHash) {
        return index(repo, commitHash, ReusableIndex.NONE);
    }

    public IndexResult index(Repository repo, String commitHash, ReusableIndex reusable) {
        long deadline = System.nanoTime() + Duration.ofSeconds(props.parseDeadlineSeconds()).toNanos();
        long fileBudget = Duration.ofSeconds(props.fileParseSeconds()).toNanos();
        Set<String> ignored = new HashSet<>(props.ignoredDirs());

        List<FileRecord> files = new ArrayList<>();
        List<SymbolRecord> symbols = new ArrayList<>();
        List<ImportRecord> imports = new ArrayList<>();
        List<IndexResult.ReferenceRecord> references = new ArrayList<>();
        List<DiagnosticRecord> diagnostics = new ArrayList<>();
        List<IndexResult.EndpointRecord> endpoints = new ArrayList<>();
        List<ReusedFile> reused = new ArrayList<>();
        int reusedSymbols = 0;
        int reusedReferences = 0;
        int parsed = 0;
        int skipped = 0;
        int javaFiles = 0;
        long totalBytes = 0;

        try (ObjectReader reader = repo.newObjectReader(); RevWalk rw = new RevWalk(reader);
             BoundedParser parser = new BoundedParser(parsers)) {
            ObjectId id = ObjectId.fromString(commitHash);
            if (!reader.has(id)) {
                throw new JobFailureException(Category.REVISION_NOT_FOUND, "Revision not found in repository.");
            }
            RevCommit commit = rw.parseCommit(id);
            try (TreeWalk tw = new TreeWalk(repo, reader)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    String path = tw.getPathString();
                    if (!path.endsWith(".java")) continue;
                    if (System.nanoTime() > deadline) {
                        throw new JobFailureException(Category.TIMEOUT, "Indexing exceeded the time limit.");
                    }
                    if (!isSafePath(path)) {
                        skipped++;
                        files.add(new FileRecord(sanitizeForStorage(path), "", "SKIPPED", "UNSAFE_PATH", 0, ""));
                        continue;
                    }
                    if (isIgnored(path, ignored)) {
                        skipped++; // counted, not listed: ignored trees can be huge
                        continue;
                    }
                    int mode = tw.getRawMode(0);
                    if (FileMode.SYMLINK.equals(mode)) {
                        skipped++;
                        files.add(new FileRecord(path, "", "SKIPPED", "SYMLINK", 0, ""));
                        continue;
                    }
                    if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode)) {
                        continue; // gitlinks (submodules) and anything else that is not a regular blob
                    }
                    if (++javaFiles > props.maxJavaFiles()) {
                        throw new JobFailureException(Category.LIMIT_EXCEEDED,
                                "More than " + props.maxJavaFiles() + " Java files in revision.");
                    }
                    ObjectId blob = tw.getObjectId(0);
                    String blobId = blob.name();
                    long size = reader.getObjectSize(blob, Constants.OBJ_BLOB);
                    if (size > props.maxFileBytes()) {
                        skipped++;
                        files.add(new FileRecord(path, "", "SKIPPED", "TOO_LARGE", size, blobId));
                        diagnostics.add(new DiagnosticRecord(path, new Diagnostic("WARNING", "FILE_TOO_LARGE",
                                "File is " + size + " bytes; limit is " + props.maxFileBytes() + ".", 0)));
                        continue;
                    }
                    totalBytes += size;
                    if (totalBytes > props.maxTotalBytes()) {
                        throw new JobFailureException(Category.LIMIT_EXCEEDED,
                                "Java sources exceed the total size limit of " + props.maxTotalBytes() + " bytes.");
                    }
                    ReusableFile same = reusable.byBlob().get(blobId);
                    if (same != null) {
                        files.add(new FileRecord(path, same.packageName(), same.status(), "", size, blobId));
                        reused.add(new ReusedFile(path, same.path()));
                        reusedSymbols += same.symbols();
                        reusedReferences += same.references();
                        if (!JavaSymbolExtractor.FileStatus.FAILED.name().equals(same.status())) parsed++;
                        checkFactLimits(symbols.size() + reusedSymbols, references.size() + reusedReferences);
                        continue;
                    }
                    ObjectLoader loader = reader.open(blob, Constants.OBJ_BLOB);
                    String source = new String(loader.getBytes((int) props.maxFileBytes()), StandardCharsets.UTF_8);
                    FileExtraction x;
                    long remaining = deadline - System.nanoTime();
                    try {
                        x = parser.parse(source, Math.min(remaining, fileBudget));
                    } catch (TimeoutException e) {
                        if (remaining <= fileBudget) {
                            throw new JobFailureException(Category.TIMEOUT, "Indexing exceeded the time limit.");
                        }
                        x = BoundedParser.timedOut(props.fileParseSeconds());
                    }
                    files.add(new FileRecord(path, x.packageName(), x.status().name(), "", size, blobId));
                    for (var s : x.symbols()) symbols.add(new SymbolRecord(path, s));
                    for (var i : x.imports()) imports.add(new ImportRecord(path, i));
                    for (var r : x.references()) references.add(new IndexResult.ReferenceRecord(path, r));
                    for (var d : x.diagnostics()) diagnostics.add(new DiagnosticRecord(path, d));
                    for (var e : x.endpoints()) endpoints.add(new IndexResult.EndpointRecord(path, e));
                    if (x.status() != JavaSymbolExtractor.FileStatus.FAILED) parsed++;
                    checkFactLimits(symbols.size() + reusedSymbols, references.size() + reusedReferences);
                }
            }
        } catch (IOException e) {
            throw new JobFailureException(Category.GIT_ERROR, "Could not read revision contents.", e);
        }
        return new IndexResult(JavaSymbolExtractor.PARSER_VERSION, parsed, skipped, files, symbols, imports, references,
                diagnostics, endpoints, reused.isEmpty() ? null : reusable.jobId(), reused);
    }

    private void checkFactLimits(int symbols, int references) {
        if (symbols > props.maxSymbols()) {
            throw new JobFailureException(Category.LIMIT_EXCEEDED, "More than " + props.maxSymbols() + " symbols in revision.");
        }
        if (references > props.maxReferences()) {
            throw new JobFailureException(Category.LIMIT_EXCEEDED,
                    "More than " + props.maxReferences() + " references in revision.");
        }
    }

    /** Git trees can contain odd names; reject anything that is not a clean relative path. */
    static boolean isSafePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || path.length() > 1024) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static boolean isIgnored(String path, Set<String> ignoredDirs) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (ignoredDirs.contains(segments[i])) return true;
        }
        return false;
    }

    private static String sanitizeForStorage(String path) {
        String s = path.replace("\0", "?");
        return s.length() > 1024 ? s.substring(0, 1024) : s;
    }
}
