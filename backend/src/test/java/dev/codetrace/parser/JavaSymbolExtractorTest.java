package dev.codetrace.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.parser.JavaSymbolExtractor.FileExtraction;
import dev.codetrace.parser.JavaSymbolExtractor.FileStatus;
import dev.codetrace.parser.JavaSymbolExtractor.Symbol;
import dev.codetrace.support.GitFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaSymbolExtractorTest {
    private final JavaSymbolExtractor extractor = new JavaSymbolExtractor();

    private static String fixture(String rel) throws IOException {
        return Files.readString(GitFixtures.fixtureRoot("demo-java").resolve(rel));
    }

    private static List<String> qualified(FileExtraction x, String kind) {
        return x.symbols().stream().filter(s -> s.kind().equals(kind)).map(Symbol::qualifiedName).toList();
    }

    @Test
    void extractsNestedAndInnerTypesWithQualifiedNames() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/Order.java"));

        assertThat(x.status()).isEqualTo(FileStatus.PARSED);
        assertThat(x.packageName()).isEqualTo("com.acme.shop");
        assertThat(qualified(x, "TYPE")).containsExactlyInAnyOrder(
                "com.acme.shop.Order",
                "com.acme.shop.Order.Status",
                "com.acme.shop.Order.Line",
                "com.acme.shop.Order.Line.Detail");
        assertThat(x.symbols()).filteredOn(s -> s.qualifiedName().equals("com.acme.shop.Order.Status"))
                .singleElement().extracting(Symbol::subtype).isEqualTo("ENUM");
        assertThat(x.symbols()).filteredOn(s -> s.qualifiedName().equals("com.acme.shop.Order.Line.Detail"))
                .singleElement().extracting(Symbol::parentQualifiedName).isEqualTo("com.acme.shop.Order.Line");
    }

    @Test
    void overloadsGetDistinctSignatures() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/Order.java"));

        assertThat(qualified(x, "METHOD")).contains(
                "com.acme.shop.Order#addLine(String)",
                "com.acme.shop.Order#addLine(String,int)",
                "com.acme.shop.Order#addLine(Line)",
                "com.acme.shop.Order#addAll(String...)",
                "com.acme.shop.Order#<init>()",
                "com.acme.shop.Order.Line#<init>(String,int)");
        // three addLine overloads + no accidental duplicates
        assertThat(x.symbols().stream().filter(s -> s.simpleName().equals("addLine"))).hasSize(3);
        assertThat(x.symbols().stream().map(Symbol::qualifiedName).distinct().count())
                .isEqualTo(x.symbols().size());
    }

    @Test
    void extractsFieldsAndEnumConstants() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/Order.java"));

        assertThat(qualified(x, "FIELD")).contains(
                "com.acme.shop.Order#lines", "com.acme.shop.Order#status",
                "com.acme.shop.Order.Line#sku", "com.acme.shop.Order.Line#quantity",
                "com.acme.shop.Order.Status#OPEN", "com.acme.shop.Order.Status#SHIPPED");
        assertThat(x.symbols()).filteredOn(s -> s.qualifiedName().endsWith("Status#PAID"))
                .singleElement().extracting(Symbol::subtype).isEqualTo("ENUM_CONSTANT");
    }

    @Test
    void recordsImportsPlainWildcardAndStatic() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/Order.java"));

        assertThat(x.imports()).extracting(JavaSymbolExtractor.Import::name).containsExactly(
                "java.util.ArrayList", "java.util.List", "java.util.Map.*", "java.util.Objects.requireNonNull");
        assertThat(x.imports()).filteredOn(JavaSymbolExtractor.Import::wildcard).hasSize(1);
        assertThat(x.imports()).filteredOn(JavaSymbolExtractor.Import::isStatic)
                .singleElement().extracting(JavaSymbolExtractor.Import::name).isEqualTo("java.util.Objects.requireNonNull");
    }

    @Test
    void handlesRecordsWithCompactConstructors() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/OrderService.java"));

        assertThat(x.symbols()).filteredOn(s -> s.qualifiedName().equals("com.acme.shop.OrderService.Receipt"))
                .singleElement().extracting(Symbol::subtype).isEqualTo("RECORD");
        assertThat(qualified(x, "FIELD")).contains(
                "com.acme.shop.OrderService.Receipt#id", "com.acme.shop.OrderService.Receipt#totalCents");
        assertThat(qualified(x, "METHOD")).contains(
                "com.acme.shop.OrderService.Receipt#<init>(String,long)",
                "com.acme.shop.OrderService#checkout(Order)",
                "com.acme.shop.OrderService#checkout(Order,String)");
    }

    @Test
    void handlesInterfacesAndDefaultMethods() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/Priced.java"));

        assertThat(x.symbols()).filteredOn(s -> s.kind().equals("TYPE")).singleElement()
                .extracting(Symbol::subtype).isEqualTo("INTERFACE");
        assertThat(qualified(x, "METHOD")).containsExactlyInAnyOrder(
                "com.acme.shop.Priced#priceCents()", "com.acme.shop.Priced#formatted()");
    }

    @Test
    void defaultPackageTypesHaveNoPackagePrefix() {
        FileExtraction x = extractor.extract("class Solo { void run() {} }");

        assertThat(x.packageName()).isEmpty();
        assertThat(qualified(x, "TYPE")).containsExactly("Solo");
        assertThat(qualified(x, "METHOD")).containsExactly("Solo#run()");
    }

    @Test
    void genericParameterTypesAreKeptUnresolvedAndNormalized() {
        // Type arguments are retained textually (JavaParser normalizes whitespace); nothing is resolved or erased.
        FileExtraction x = extractor.extract(
                "package p; import java.util.*; class G { void put(Map<String, List<Integer>> m, int[] a) {} }");

        assertThat(qualified(x, "METHOD")).containsExactly("p.G#put(Map<String,List<Integer>>,int[])");
    }

    @Test
    void malformedFileProducesDiagnosticsAndNeverThrows() throws IOException {
        FileExtraction x = extractor.extract(fixture("src/main/java/com/acme/shop/Broken.java"));

        assertThat(x.status()).isIn(FileStatus.PARTIAL, FileStatus.FAILED);
        assertThat(x.diagnostics()).isNotEmpty();
        assertThat(x.diagnostics()).allSatisfy(d -> {
            assertThat(d.code()).isEqualTo("SYNTAX_ERROR");
            assertThat(d.severity()).isEqualTo("ERROR");
            assertThat(d.line()).isPositive();
            assertThat(d.message().length()).isLessThanOrEqualTo(300);
        });
    }

    @Test
    void garbageAndEmptyInputAreSafe() {
        assertThat(extractor.extract("").status()).isEqualTo(FileStatus.PARSED);
        assertThat(extractor.extract("").symbols()).isEmpty();

        FileExtraction junk = extractor.extract("}}}{{{ \u0000 not java at all ;;;");
        assertThat(junk.status()).isNotEqualTo(FileStatus.PARSED);
        assertThat(junk.diagnostics()).isNotEmpty();
    }

    @Test
    void pathologicallyDeepNestingDoesNotCrashTheExtractor() {
        String deep = "class A { void f() { " + "{".repeat(3000) + "}".repeat(3000) + " } }";

        FileExtraction x = extractor.extract(deep);

        assertThat(x.status()).isNotNull(); // must return, whatever the status
    }

    @Test
    void symbolsCarryLineRanges() throws IOException {
        Path file = GitFixtures.fixtureRoot("demo-java").resolve("src/main/java/com/acme/shop/Priced.java");
        FileExtraction x = extractor.extract(Files.readString(file));

        assertThat(x.symbols()).allSatisfy(s -> {
            assertThat(s.startLine()).isPositive();
            assertThat(s.endLine()).isGreaterThanOrEqualTo(s.startLine());
        });
    }
}
