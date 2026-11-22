package dev.codetrace.ingestion.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.ingestion.parser.JavaSymbolExtractor.FileExtraction;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.Reference;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Raw reference facts: what the text says, never what it resolves to. */
class JavaReferenceExtractionTest {
    private final JavaSymbolExtractor extractor = new JavaSymbolExtractor();

    private FileExtraction parse(String body) {
        return extractor.extract("package p;\n" + body);
    }

    private static Stream<Reference> of(FileExtraction x, String kind) {
        return x.references().stream().filter(r -> r.kind().equals(kind));
    }

    private static List<String> names(FileExtraction x, String kind, String owner) {
        return of(x, kind).filter(r -> r.ownerQualifiedName().equals(owner)).map(Reference::name).toList();
    }

    private static Reference call(FileExtraction x, String owner, String name) {
        return of(x, "CALLS").filter(r -> r.ownerQualifiedName().equals(owner) && r.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no call " + name + " in " + owner + ": " + x.references()));
    }

    // ---- types -----------------------------------------------------------------------------------------------

    @Test
    void extendsAndImplementsAreRecordedWithTheirTypeArguments() {
        FileExtraction x = parse("class A extends B implements C, D<E> {}");

        assertThat(names(x, "EXTENDS", "p.A")).containsExactly("B");
        assertThat(names(x, "IMPLEMENTS", "p.A")).containsExactly("C", "D");
        assertThat(names(x, "REFERENCES", "p.A")).containsExactly("E");
    }

    @Test
    void interfaceExtendsAndRecordEnumImplementsUseTheRightKinds() {
        FileExtraction x = parse("interface I extends J {} record R(int a) implements K {} enum E implements L { X }");

        assertThat(names(x, "EXTENDS", "p.I")).containsExactly("J");
        assertThat(names(x, "IMPLEMENTS", "p.R")).containsExactly("K");
        assertThat(names(x, "IMPLEMENTS", "p.E")).containsExactly("L");
    }

    @Test
    void typeUsagesAreAttributedToTheDeclaringMember() {
        FileExtraction x = parse("""
                import java.util.List;
                class A {
                    List<Order> items;
                    Result run(Input in) throws Failure { Helper h = null; return null; }
                }""");

        assertThat(names(x, "REFERENCES", "p.A#items")).containsExactlyInAnyOrder("List", "Order");
        assertThat(names(x, "REFERENCES", "p.A#run(Input)")).containsExactlyInAnyOrder("Result", "Input", "Failure", "Helper");
    }

    @Test
    void qualifiedTypeIsOneReferenceNotTwo() {
        FileExtraction x = parse("class A { java.util.Map.Entry<String, Order> e; }");

        assertThat(names(x, "REFERENCES", "p.A#e")).containsExactlyInAnyOrder("java.util.Map.Entry", "String", "Order");
    }

    @Test
    void genericTypeVariablesAreNotReferences() {
        FileExtraction x = parse("class Box<T> { T value; <U> U map(T in, U other) { return other; } }");

        assertThat(x.references()).noneMatch(r -> r.kind().equals("REFERENCES")
                && (r.name().equals("T") || r.name().equals("U")));
    }

    // ---- calls -----------------------------------------------------------------------------------------------

    @Test
    void unqualifiedThisAndSuperCallsHaveTheirReceiverKind() {
        FileExtraction x = parse("class A extends B { void go() { helper(1, 2); this.a(); super.b(1); } }");

        Reference helper = call(x, "p.A#go()", "helper");
        assertThat(helper.receiverKind()).isEqualTo("NONE");
        assertThat(helper.argCount()).isEqualTo(2);
        assertThat(call(x, "p.A#go()", "a").receiverKind()).isEqualTo("THIS");
        assertThat(call(x, "p.A#go()", "b").receiverKind()).isEqualTo("SUPER");
        assertThat(call(x, "p.A#go()", "b").argCount()).isEqualTo(1);
    }

    @Test
    void receiverTypeComesFromDeclaredLocalsParametersAndFields() {
        FileExtraction x = parse("""
                class A {
                    private final SourceRepository repo;
                    void go(Order param) {
                        Cart cart = new Cart();
                        cart.add(param);
                        param.total();
                        repo.save(param);
                        this.repo.flush();
                    }
                }""");

        String owner = "p.A#go(Order)";
        assertThat(call(x, owner, "add").receiverType()).isEqualTo("Cart");
        assertThat(call(x, owner, "total").receiverType()).isEqualTo("Order");
        assertThat(call(x, owner, "save").receiverType()).isEqualTo("SourceRepository");
        assertThat(call(x, owner, "flush").receiverType()).isEqualTo("SourceRepository");
        assertThat(call(x, owner, "add").receiverKind()).isEqualTo("TYPE");
    }

    @Test
    void aLocalShadowsAFieldOfTheSameName() {
        FileExtraction x = parse("class A { SourceRepository r; void go() { Cache r = null; r.get(); } }");

        assertThat(call(x, "p.A#go()", "get").receiverType()).isEqualTo("Cache");
    }

    @Test
    void genericReceiverKeepsTheRawTypeName() {
        FileExtraction x = parse("class A { void go(java.util.List<Order> items) { items.add(null); } }");

        assertThat(call(x, "p.A#go(java.util.List<Order>)", "add").receiverType()).isEqualTo("java.util.List");
    }

    @Test
    void staticStyleReceiversAreTypeNames() {
        FileExtraction x = parse("class A { void go() { Math.max(1, 2); java.util.Collections.sort(null); } }");

        assertThat(call(x, "p.A#go()", "max").receiverType()).isEqualTo("Math");
        assertThat(call(x, "p.A#go()", "sort").receiverType()).isEqualTo("java.util.Collections");
    }

    @Test
    void unknowableReceiversStayUnknownInsteadOfGuessing() {
        FileExtraction x = parse("""
                class A<T> {
                    T generic;
                    void go(T t) {
                        var v = make();
                        v.one();
                        System.out.println("x");
                        make().two();
                        t.three();
                        generic.four();
                        int[] arr = null;
                        arr.five();
                    }
                }""");

        String owner = "p.A#go(T)";
        for (String name : List.of("one", "println", "two", "three", "four", "five")) {
            assertThat(call(x, owner, name).receiverKind()).as(name).isEqualTo("UNKNOWN");
            assertThat(call(x, owner, name).receiverType()).as(name).isEmpty();
        }
    }

    @Test
    void creationsAndCastsContributeTheirTypes() {
        FileExtraction x = parse("class A { void go(Object o) { new Foo().bar(); ((Baz) o).qux(); } }");

        assertThat(call(x, "p.A#go(Object)", "bar").receiverType()).isEqualTo("Foo");
        assertThat(call(x, "p.A#go(Object)", "qux").receiverType()).isEqualTo("Baz");
    }

    @Test
    void constructorInvocationsAreCallsToInit() {
        FileExtraction x = parse("""
                class A extends B {
                    A() { this(1); }
                    A(int n) { super(n, 2); }
                    void make() { new C(1, 2, 3); }
                }""");

        assertThat(call(x, "p.A#<init>()", "<init>").receiverKind()).isEqualTo("THIS");
        assertThat(call(x, "p.A#<init>(int)", "<init>").receiverKind()).isEqualTo("SUPER");
        assertThat(call(x, "p.A#<init>(int)", "<init>").argCount()).isEqualTo(2);
        Reference created = call(x, "p.A#make()", "<init>");
        assertThat(created.receiverType()).isEqualTo("C");
        assertThat(created.argCount()).isEqualTo(3);
        assertThat(names(x, "REFERENCES", "p.A#make()")).contains("C");
    }

    // ---- ownership -------------------------------------------------------------------------------------------

    @Test
    void callsInInitializersAndLambdasAreOwnedByTheRightDeclaration() {
        FileExtraction x = parse("""
                class A {
                    int f = compute();
                    static { boot(); }
                    void go(java.util.List<String> xs) { xs.forEach(s -> log(s)); }
                }""");

        assertThat(call(x, "p.A#f", "compute")).isNotNull();
        assertThat(call(x, "p.A", "boot")).isNotNull();            // initializer blocks belong to the type
        assertThat(call(x, "p.A#go(java.util.List<String>)", "log")).isNotNull(); // lambda body belongs to the method
    }

    @Test
    void everyReferenceHasALineNumber() {
        FileExtraction x = parse("class A extends B { void go() { run(); } }");

        assertThat(x.references()).isNotEmpty().allSatisfy(r -> assertThat(r.line()).isPositive());
    }

    // ---- argument type hints -----------------------------------------------------------------------------------------

    @Test
    void callArgumentsCarryTypeHintsFromLiteralsConstructionsCastsAndDeclaredNames() {
        FileExtraction x = parse("""
                class A {
                    private final Foo field = null;
                    void go(String name, int[] data, Foo foo) {
                        long big = 1L;
                        use(true, 'c', 1, 2L, 1.5, 2f, "s", null, name, data, foo, field, big, this, new Bar(), (Baz) o, Foo.class);
                    }
                }""");

        assertThat(call(x, "p.A#go(String,int[],Foo)", "use").argTypes()).containsExactly(
                "boolean", "char", "int", "long", "double", "float", "String", "null", "String", "int[]", "Foo", "Foo",
                "long", "A", "Bar", "Baz", "Class");
    }

    @Test
    void argumentsWhoseTypeTheTextDoesNotRevealAreEmptyHints() {
        FileExtraction x = parse("class A<T> { void go(T t) { use(make(), t, x -> x, A::go, flag ? 1 : 2, undeclared); } }");

        assertThat(call(x, "p.A#go(T)", "use").argTypes()).containsExactly("", "", "", "", "", "");
    }

    @Test
    void concatenationComparisonsAndSignedNumbersAreTyped() {
        FileExtraction x = parse("class A { void go(int n, boolean ok) { use(\"a\" + n, n > 1, !ok, -5, -2.5); } }");

        assertThat(call(x, "p.A#go(int,boolean)", "use").argTypes())
                .containsExactly("String", "boolean", "boolean", "int", "double");
    }

    @Test
    void constructorCallsRecordArgumentHintsToo() {
        FileExtraction x = parse("class A extends B { A(String s) { super(s, 1); } A() { this(\"x\"); } void make() { new C(2L); } }");

        assertThat(call(x, "p.A#<init>(String)", "<init>").argTypes()).containsExactly("String", "int");
        assertThat(call(x, "p.A#<init>()", "<init>").argTypes()).containsExactly("String");
        assertThat(call(x, "p.A#make()", "<init>").argTypes()).containsExactly("long");
    }

    @Test
    void malformedFilesStillReturnWhateverWasRecoverable() {
        FileExtraction x = extractor.extract("package p; class A { void ok() { run(); } void bad( { } }");

        assertThat(x.status()).isNotEqualTo(JavaSymbolExtractor.FileStatus.PARSED);
        assertThat(x.diagnostics()).isNotEmpty();
    }
}
