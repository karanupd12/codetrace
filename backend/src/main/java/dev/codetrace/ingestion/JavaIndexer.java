package dev.codetrace.ingestion;

import dev.codetrace.config.IngestionProperties;
import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.parser.JavaSymbolExtractor;
import dev.codetrace.parser.JavaSymbolExtractor.Diagnostic;
import dev.codetrace.parser.JavaSymbolExtractor.FileExtraction;
import dev.codetrace.symbols.IndexResult;
import dev.codetrace.symbols.IndexResult.DiagnosticRecord;
import dev.codetrace.symbols.IndexResult.FileRecord;
import dev.codetrace.symbols.IndexResult.ImportRecord;
import dev.codetrace.symbols.IndexResult.SymbolRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Enforces file-count, per-file, total-byte, symbol-count and wall-clock limits.
 */
@Component
public class JavaIndexer {
    private final IngestionProperties props;

    public JavaIndexer(IngestionProperties props) {
        this.props = props;
    }

    public IndexResult index(Repository repo, String commitHash) {
        long deadline = System.nanoTime() + Duration.ofSeconds(props.parseDeadlineSeconds()).toNanos();
        JavaSymbolExtractor extractor = new JavaSymbolExtractor();
        Set<String> ignored = new HashSet<>(props.ignoredDirs());

        List<FileRecord> files = new ArrayList<>();
        List<SymbolRecord> symbols = new ArrayList<>();
        List<ImportRecord> imports = new ArrayList<>();
        List<IndexResult.ReferenceRecord> references = new ArrayList<>();
        List<DiagnosticRecord> diagnostics = new ArrayList<>();
        int parsed = 0;
        int skipped = 0;
        int javaFiles = 0;
        long totalBytes = 0;

        try (ObjectReader reader = repo.newObjectReader(); RevWalk rw = new RevWalk(reader)) {
            ObjectId id = ObjectId.fromString(commitHash);
            if (!reader.has(id)) {
                throw new IngestionException(Category.REVISION_NOT_FOUND, "Revision not found in repository.");
            }
            RevCommit commit = rw.parseCommit(id);
            try (TreeWalk tw = new TreeWalk(repo, reader)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    String path = tw.getPathString();
                    if (!path.endsWith(".java")) continue;
                    if (System.nanoTime() > deadline) {
                        throw new IngestionException(Category.TIMEOUT, "Indexing exceeded the time limit.");
                    }
                    if (!isSafePath(path)) {
                        skipped++;
                        files.add(new FileRecord(sanitizeForStorage(path), "", "SKIPPED", "UNSAFE_PATH", 0));
                        continue;
                    }
                    if (isIgnored(path, ignored)) {
                        skipped++; // counted, not listed: ignored trees can be huge
                        continue;
                    }
                    int mode = tw.getRawMode(0);
                    if (FileMode.SYMLINK.equals(mode)) {
                        skipped++;
                        files.add(new FileRecord(path, "", "SKIPPED", "SYMLINK", 0));
                        continue;
                    }
                    if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode)) {
                        continue; // gitlinks (submodules) and anything else that is not a regular blob
                    }
                    if (++javaFiles > props.maxJavaFiles()) {
                        throw new IngestionException(Category.LIMIT_EXCEEDED,
                                "More than " + props.maxJavaFiles() + " Java files in revision.");
                    }
                    ObjectLoader loader = reader.open(tw.getObjectId(0));
                    long size = loader.getSize();
                    if (size > props.maxFileBytes()) {
                        skipped++;
                        files.add(new FileRecord(path, "", "SKIPPED", "TOO_LARGE", size));
                        diagnostics.add(new DiagnosticRecord(path, new Diagnostic("WARNING", "FILE_TOO_LARGE",
                                "File is " + size + " bytes; limit is " + props.maxFileBytes() + ".", 0)));
                        continue;
                    }
                    totalBytes += size;
                    if (totalBytes > props.maxTotalBytes()) {
                        throw new IngestionException(Category.LIMIT_EXCEEDED,
                                "Java sources exceed the total size limit of " + props.maxTotalBytes() + " bytes.");
                    }
                    String source = new String(loader.getBytes((int) props.maxFileBytes()), StandardCharsets.UTF_8);
                    FileExtraction x = extractor.extract(source);
                    files.add(new FileRecord(path, x.packageName(), x.status().name(), "", size));
                    for (var s : x.symbols()) symbols.add(new SymbolRecord(path, s));
                    for (var i : x.imports()) imports.add(new ImportRecord(path, i));
                    for (var r : x.references()) references.add(new IndexResult.ReferenceRecord(path, r));
                    for (var d : x.diagnostics()) diagnostics.add(new DiagnosticRecord(path, d));
                    if (x.status() != JavaSymbolExtractor.FileStatus.FAILED) parsed++;
                    if (symbols.size() > props.maxSymbols()) {
                        throw new IngestionException(Category.LIMIT_EXCEEDED,
                                "More than " + props.maxSymbols() + " symbols in revision.");
                    }
                    if (references.size() > props.maxReferences()) {
                        throw new IngestionException(Category.LIMIT_EXCEEDED,
                                "More than " + props.maxReferences() + " references in revision.");
                    }
                }
            }
        } catch (IOException e) {
            throw new IngestionException(Category.GIT_ERROR, "Could not read revision contents.", e);
        }
        return new IndexResult(JavaSymbolExtractor.PARSER_VERSION, parsed, skipped, files, symbols, imports, references,
                diagnostics);
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
