package dev.codetrace.impact.change;

import dev.codetrace.impact.change.ChangeSet.ChangeType;
import dev.codetrace.impact.change.ChangeSet.FileChange;
import dev.codetrace.impact.change.ChangeSet.Hunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns changed line ranges into changed symbols. A hunk marks the <em>innermost</em> declaration whose own lines it
 * touches: an edit inside a method marks the method, not its class; the class is marked only when its header or lines
 * outside every member change. Hunks that touch no declaration (imports, comments between members) are counted, not
 * guessed at.
 *
 * <p>A symbol present on both sides is {@code MODIFIED}; only in the head, {@code ADDED}; only in the base,
 * {@code REMOVED}. A changed signature therefore appears as a removal plus an addition, which is exactly what breaks
 * callers. A symbol that disappears from one file and appears in another (a moved class) is {@code MODIFIED}.
 */
@Component
public class ChangedSymbolMapper {

    public enum Kind { ADDED, MODIFIED, REMOVED }

    /** {@code base} is null for ADDED, {@code head} is null for REMOVED. */
    public record ChangedSymbol(Kind kind, SymbolRef base, SymbolRef head) {
        public SymbolRef any() {
            return head != null ? head : base;
        }
    }

    public record Mapping(List<ChangedSymbol> symbols, int unmappedHunks) {
    }

    public Mapping map(ChangeSet changes, Map<String, List<SymbolRef>> baseByPath, Map<String, List<SymbolRef>> headByPath) {
        Map<String, SymbolRef> baseTouched = new LinkedHashMap<>();
        Map<String, SymbolRef> headTouched = new LinkedHashMap<>();
        Map<String, SymbolRef> baseKnown = new LinkedHashMap<>();
        Map<String, SymbolRef> headKnown = new LinkedHashMap<>();
        int unmapped = 0;

        for (FileChange f : changes.javaFiles()) {
            List<SymbolRef> oldNodes = f.oldPath() == null ? List.of() : baseByPath.getOrDefault(f.oldPath(), List.of());
            List<SymbolRef> newNodes = f.newPath() == null ? List.of() : headByPath.getOrDefault(f.newPath(), List.of());
            oldNodes.forEach(n -> baseKnown.putIfAbsent(n.identity(), n));
            newNodes.forEach(n -> headKnown.putIfAbsent(n.identity(), n));

            if (f.type() == ChangeType.ADDED) {
                newNodes.forEach(n -> headTouched.putIfAbsent(n.identity(), n));
                continue;
            }
            if (f.type() == ChangeType.DELETED) {
                oldNodes.forEach(n -> baseTouched.putIfAbsent(n.identity(), n));
                continue;
            }
            for (Hunk h : f.hunks()) {
                boolean hit = false;
                hit |= touch(oldNodes, h, true, baseTouched);
                hit |= touch(newNodes, h, false, headTouched);
                if (!hit) unmapped++;
            }
        }

        // every identity touched on either side, classified by where it exists; baseKnown/headKnown span all changed
        // files, so a symbol that moved between files is MODIFIED rather than removed plus added
        Map<String, ChangedSymbol> out = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(baseTouched.keySet());
        headTouched.keySet().stream().filter(k -> !baseTouched.containsKey(k)).forEach(ids::add);
        for (String id : ids) {
            SymbolRef base = baseTouched.getOrDefault(id, baseKnown.get(id));
            SymbolRef head = headTouched.getOrDefault(id, headKnown.get(id));
            Kind kind = base != null && head != null ? Kind.MODIFIED : head != null ? Kind.ADDED : Kind.REMOVED;
            out.put(id, new ChangedSymbol(kind, base, head));
        }

        List<ChangedSymbol> sorted = new ArrayList<>(out.values());
        sorted.sort(Comparator.<ChangedSymbol, String>comparing(c -> c.any().qualifiedName())
                .thenComparing(c -> c.any().signature()).thenComparing(c -> c.kind().name()));
        return new Mapping(List.copyOf(sorted), unmapped);
    }

    /** Adds every node whose own lines the hunk touches on the old or new side. Returns whether any node was hit. */
    private boolean touch(List<SymbolRef> nodes, Hunk h, boolean oldSide, Map<String, SymbolRef> into) {
        int from = oldSide ? h.oldStart() : h.newStart();
        int to = oldSide ? h.oldEnd() : h.newEnd();
        boolean hit = false;
        if (to < from) { // empty on this side: a pure insertion/deletion point on the other side
            return false;
        }
        for (SymbolRef n : nodes) {
            if (ownLinesIntersect(n, nodes, from, to)) {
                into.putIfAbsent(n.identity(), n);
                hit = true;
            }
        }
        return hit;
    }

    /** True when [from,to] overlaps the node's range minus the ranges of the declarations nested inside it. */
    private static boolean ownLinesIntersect(SymbolRef n, List<SymbolRef> all, int from, int to) {
        if (n.endLine() < from || n.startLine() > to) return false;
        List<int[]> free = new ArrayList<>();
        free.add(new int[] {Math.max(n.startLine(), from), Math.min(n.endLine(), to)});
        for (SymbolRef child : all) {
            if (!n.contains(child)) continue;
            List<int[]> next = new ArrayList<>();
            for (int[] seg : free) {
                if (child.endLine() < seg[0] || child.startLine() > seg[1]) {
                    next.add(seg);
                    continue;
                }
                if (seg[0] < child.startLine()) next.add(new int[] {seg[0], child.startLine() - 1});
                if (seg[1] > child.endLine()) next.add(new int[] {child.endLine() + 1, seg[1]});
            }
            free = next;
            if (free.isEmpty()) return false;
        }
        return !free.isEmpty();
    }
}
