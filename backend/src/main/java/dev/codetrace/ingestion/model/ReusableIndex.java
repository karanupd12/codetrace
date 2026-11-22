package dev.codetrace.ingestion.model;

import java.util.Map;
import java.util.UUID;

/**
 * The files of an earlier index whose facts a new index may copy, keyed by Git blob id. Extraction reads only a file's
 * content, so the same blob always yields the same facts, whatever its path.
 */
public record ReusableIndex(UUID jobId, Map<String, ReusableFile> byBlob) {
    public static final ReusableIndex NONE = new ReusableIndex(null, Map.of());

    public record ReusableFile(String path, String packageName, String status, int symbols, int references) {
    }
}
