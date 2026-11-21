package dev.codetrace.impact.change;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.impact.change.ChangeSet.ChangeType;
import dev.codetrace.impact.change.ChangeSet.FileChange;
import dev.codetrace.impact.change.ChangeSet.Hunk;
import dev.codetrace.impact.change.ChangedSymbolMapper.ChangedSymbol;
import dev.codetrace.impact.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.change.ChangedSymbolMapper.Mapping;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangedSymbolMapperTest {
    private final ChangedSymbolMapper mapper = new ChangedSymbolMapper();

    // Order.java (head layout): lines 1-3 imports/package, class 5-40, field 6, method a 8-15, method b 17-25,
    // nested type 27-38 containing method n 28-36
    private static SymbolRef type(String qn, String path, int s, int e) {
        return new SymbolRef(UUID.randomUUID(), "TYPE", qn, "", path, s, e, "public");
    }

    private static SymbolRef method(String qn, String sig, String path, int s, int e) {
        return new SymbolRef(UUID.randomUUID(), "METHOD", qn, sig, path, s, e, "public");
    }

    private static SymbolRef field(String qn, String path, int s, int e) {
        return new SymbolRef(UUID.randomUUID(), "FIELD", qn, "", path, s, e, "private");
    }

    private static final String P = "src/main/java/p/Order.java";

    private List<SymbolRef> layout() {
        return List.of(
                type("p.Order", P, 5, 40),
                field("p.Order#total", P, 6, 6),
                method("p.Order#a", "()", P, 8, 15),
                method("p.Order#b", "(int)", P, 17, 25),
                type("p.Order.Inner", P, 27, 38),
                method("p.Order.Inner#n", "()", P, 28, 36));
    }

    private static ChangeSet cs(FileChange... files) {
        return new ChangeSet("b", "h", List.of(files));
    }

    private static FileChange modified(Hunk... hunks) {
        return new FileChange(ChangeType.MODIFIED, P, P, List.of(hunks), false);
    }

    private Mapping run(ChangeSet cs, List<SymbolRef> base, List<SymbolRef> head) {
        return mapper.map(cs, Map.of(P, base), Map.of(P, head));
    }

    private static List<String> summary(Mapping m) {
        return m.symbols().stream().map(c -> c.kind() + " " + c.any().qualifiedName()
                + (c.any().signature().isEmpty() ? "" : c.any().signature())).toList();
    }

    @Test
    void editInsideAMethodMarksOnlyThatMethod() {
        Mapping m = run(cs(modified(new Hunk(10, 11, 10, 11))), layout(), layout());

        assertThat(summary(m)).containsExactly("MODIFIED p.Order#a()");
        assertThat(m.unmappedHunks()).isZero();
    }

    @Test
    void editInsideANestedMethodDoesNotMarkItsEnclosingTypes() {
        Mapping m = run(cs(modified(new Hunk(30, 30, 30, 30))), layout(), layout());

        assertThat(summary(m)).containsExactly("MODIFIED p.Order.Inner#n()");
    }

    @Test
    void headerEditMarksTheTypeButNotItsMembers() {
        Mapping m = run(cs(modified(new Hunk(5, 5, 5, 5))), layout(), layout()); // "public class Order extends X {"

        assertThat(summary(m)).containsExactly("MODIFIED p.Order");
    }

    @Test
    void lineBetweenMembersBelongsToTheEnclosingType() {
        Mapping m = run(cs(modified(new Hunk(16, 16, 16, 16))), layout(), layout()); // blank/comment line between a and b

        assertThat(summary(m)).containsExactly("MODIFIED p.Order");
    }

    @Test
    void fieldEditMarksTheField() {
        Mapping m = run(cs(modified(new Hunk(6, 6, 6, 6))), layout(), layout());

        assertThat(summary(m)).containsExactly("MODIFIED p.Order#total");
    }

    @Test
    void hunkSpanningTwoMethodsMarksBoth() {
        Mapping m = run(cs(modified(new Hunk(14, 18, 14, 18))), layout(), layout());

        assertThat(summary(m)).containsExactlyInAnyOrder("MODIFIED p.Order#a()", "MODIFIED p.Order#b(int)", "MODIFIED p.Order");
        // lines 16 is outside both methods, so the enclosing type's own lines are touched too
    }

    @Test
    void pureInsertionInsideAMethodIsAModification() {
        // two lines inserted at 10-11 of the head; nothing removed (old side empty)
        List<SymbolRef> head = List.of(
                type("p.Order", P, 5, 42), field("p.Order#total", P, 6, 6), method("p.Order#a", "()", P, 8, 17),
                method("p.Order#b", "(int)", P, 19, 27));
        Mapping m = run(cs(modified(new Hunk(10, 9, 10, 11))), layout(), head);

        assertThat(summary(m)).containsExactly("MODIFIED p.Order#a()");
    }

    @Test
    void pureDeletionInsideAMethodIsAModification() {
        Mapping m = run(cs(modified(new Hunk(10, 11, 10, 9))), layout(), layout());

        assertThat(summary(m)).containsExactly("MODIFIED p.Order#a()");
    }

    @Test
    void deletedMethodIsRemovedAndNewMethodIsAdded() {
        List<SymbolRef> head = List.of(
                type("p.Order", P, 5, 40), field("p.Order#total", P, 6, 6), method("p.Order#a", "()", P, 8, 15),
                method("p.Order#c", "()", P, 17, 25)); // b(int) replaced by c()
        Mapping m = run(cs(modified(new Hunk(17, 25, 17, 25))), layout(), head);

        assertThat(summary(m)).containsExactlyInAnyOrder("REMOVED p.Order#b(int)", "ADDED p.Order#c()");
        ChangedSymbol removed = m.symbols().stream().filter(c -> c.kind() == Kind.REMOVED).findFirst().orElseThrow();
        assertThat(removed.head()).isNull();
        assertThat(removed.base().nodeId()).isNotNull();
    }

    @Test
    void changedSignatureIsRemovalPlusAddition() {
        List<SymbolRef> head = List.of(
                type("p.Order", P, 5, 40), field("p.Order#total", P, 6, 6), method("p.Order#a", "(String)", P, 8, 15),
                method("p.Order#b", "(int)", P, 17, 25), type("p.Order.Inner", P, 27, 38),
                method("p.Order.Inner#n", "()", P, 28, 36));
        Mapping m = run(cs(modified(new Hunk(8, 8, 8, 8))), layout(), head);

        assertThat(summary(m)).containsExactlyInAnyOrder("REMOVED p.Order#a()", "ADDED p.Order#a(String)");
    }

    @Test
    void addedFileMarksEveryDeclarationAdded() {
        FileChange added = new FileChange(ChangeType.ADDED, null, P, List.of(new Hunk(1, 0, 1, 40)), false);

        Mapping m = mapper.map(cs(added), Map.of(), Map.of(P, layout()));

        assertThat(m.symbols()).hasSize(6).allMatch(c -> c.kind() == Kind.ADDED && c.base() == null);
    }

    @Test
    void deletedFileMarksEveryDeclarationRemoved() {
        FileChange deleted = new FileChange(ChangeType.DELETED, P, null, List.of(new Hunk(1, 40, 1, 0)), false);

        Mapping m = mapper.map(cs(deleted), Map.of(P, layout()), Map.of());

        assertThat(m.symbols()).hasSize(6).allMatch(c -> c.kind() == Kind.REMOVED && c.head() == null);
    }

    @Test
    void renamedFileUsesOldPathForTheBaseAndNewPathForTheHead() {
        String newP = "src/main/java/p/Renamed.java";
        List<SymbolRef> head = layout().stream()
                .map(s -> new SymbolRef(UUID.randomUUID(), s.nodeType(), s.qualifiedName(), s.signature(), newP,
                        s.startLine(), s.endLine(), s.modifiers())).toList();
        FileChange renamed = new FileChange(ChangeType.RENAMED, P, newP, List.of(new Hunk(10, 10, 10, 10)), false);

        Mapping m = mapper.map(cs(renamed), Map.of(P, layout()), Map.of(newP, head));

        assertThat(summary(m)).containsExactly("MODIFIED p.Order#a()");
        assertThat(m.symbols().get(0).base().path()).isEqualTo(P);
        assertThat(m.symbols().get(0).head().path()).isEqualTo(newP);
    }

    @Test
    void symbolMovedBetweenFilesIsModifiedNotRemovedAndAdded() {
        String other = "src/main/java/p/Other.java";
        SymbolRef moved = method("p.Util#helper", "()", P, 8, 15);
        SymbolRef movedHead = method("p.Util#helper", "()", other, 3, 10);
        FileChange from = new FileChange(ChangeType.MODIFIED, P, P, List.of(new Hunk(8, 15, 8, 7)), false);
        FileChange to = new FileChange(ChangeType.MODIFIED, other, other, List.of(new Hunk(3, 2, 3, 10)), false);

        Mapping m = mapper.map(cs(from, to), Map.of(P, List.of(moved), other, List.of()),
                Map.of(P, List.of(), other, List.of(movedHead)));

        assertThat(summary(m)).containsExactly("MODIFIED p.Util#helper()");
        assertThat(m.symbols().get(0).base().path()).isEqualTo(P);
        assertThat(m.symbols().get(0).head().path()).isEqualTo(other);
    }

    @Test
    void importOnlyHunkIsCountedAsUnmapped() {
        Mapping m = run(cs(modified(new Hunk(2, 2, 2, 3))), layout(), layout());

        assertThat(m.symbols()).isEmpty();
        assertThat(m.unmappedHunks()).isEqualTo(1);
    }

    @Test
    void nonJavaAndBinaryChangesAreIgnored() {
        FileChange md = new FileChange(ChangeType.MODIFIED, "README.md", "README.md", List.of(new Hunk(1, 1, 1, 1)), false);
        FileChange bin = new FileChange(ChangeType.MODIFIED, "a.java", "a.java", List.of(), true);

        Mapping m = mapper.map(cs(md, bin), Map.of(), Map.of());

        assertThat(m.symbols()).isEmpty();
        assertThat(m.unmappedHunks()).isZero();
    }

    @Test
    void declarationsSharingOneLineAreBothMarked() {
        SymbolRef a = field("p.Order#x", P, 6, 6); // int x, y;
        SymbolRef b = field("p.Order#y", P, 6, 6);
        List<SymbolRef> nodes = List.of(type("p.Order", P, 5, 10), a, b);

        Mapping m = run(cs(modified(new Hunk(6, 6, 6, 6))), nodes, nodes);

        assertThat(summary(m)).containsExactlyInAnyOrder("MODIFIED p.Order#x", "MODIFIED p.Order#y");
    }

    @Test
    void resultIsSortedAndDeterministic() {
        ChangeSet change = cs(modified(new Hunk(18, 19, 18, 19), new Hunk(10, 10, 10, 10), new Hunk(30, 30, 30, 30)));

        List<String> first = summary(run(change, layout(), layout()));
        List<String> second = summary(run(change, layout(), layout()));

        assertThat(first).isEqualTo(second).containsExactly("MODIFIED p.Order#a()", "MODIFIED p.Order#b(int)",
                "MODIFIED p.Order.Inner#n()");
    }
}
