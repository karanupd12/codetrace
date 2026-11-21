package dev.codetrace.change;

import java.util.List;

/** What differs between two commits, at file and line-range level. Computed from Git objects only. */
public record ChangeSet(String baseCommit, String headCommit, List<FileChange> files) {

    public enum ChangeType { ADDED, MODIFIED, DELETED, RENAMED }

    /**
     * A changed line range. Lines are 1-based and inclusive; a side with no lines (pure insertion or deletion) has
     * {@code end = start - 1}. {@code old*} refers to the base commit's file, {@code new*} to the head commit's.
     */
    public record Hunk(int oldStart, int oldEnd, int newStart, int newEnd) {
        public boolean touchesOld(int from, int to) {
            return oldEnd >= oldStart && oldStart <= to && oldEnd >= from;
        }

        public boolean touchesNew(int from, int to) {
            return newEnd >= newStart && newStart <= to && newEnd >= from;
        }
    }

    /** {@code oldPath} is null for added files and {@code newPath} is null for deleted ones. */
    public record FileChange(ChangeType type, String oldPath, String newPath, List<Hunk> hunks, boolean binary) {
        public String path() {
            return newPath != null ? newPath : oldPath;
        }

        public boolean isJava() {
            return path().endsWith(".java");
        }
    }

    public List<FileChange> javaFiles() {
        return files.stream().filter(f -> f.isJava() && !f.binary()).toList();
    }

    public int nonJavaFileCount() {
        return (int) files.stream().filter(f -> !f.isJava() || f.binary()).count();
    }
}
