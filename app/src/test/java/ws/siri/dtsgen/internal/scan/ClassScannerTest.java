package ws.siri.dtsgen.internal.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the jar-in-jar case, which is how Fabric mods ship: the jar named as a source may
 * carry no classes at all, only one jar per module under {@code META-INF/jars/}.
 */
class ClassScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void findsClassesInsideANestedJar() throws IOException {
        // Shaped like fabric-api: the outer jar holds a nested jar and nothing else.
        byte[] inner = jar(Map.of("ws/siri/fixture/Nested.class", classFile("ws/siri/fixture/Nested")));
        Path outer = writeJar("outer.jar", Map.of("META-INF/jars/inner.jar", inner));

        ScanResult result = ClassScanner.scan(List.of(outer), List.of());

        assertTrue(result.emittableTypes().contains("ws/siri/fixture/Nested"),
                "nested class should be emittable, got " + result.emittableTypes());
        assertEquals(List.of(), result.unreadableClasses());
    }

    @Test
    void findsTopLevelAndNestedClassesTogether() throws IOException {
        byte[] inner = jar(Map.of("ws/siri/fixture/Nested.class", classFile("ws/siri/fixture/Nested")));
        // LinkedHashMap: the outer jar's entry order decides scan order, so keep it fixed.
        Map<String, byte[]> outerEntries = new LinkedHashMap<>();
        outerEntries.put("ws/siri/fixture/TopLevel.class", classFile("ws/siri/fixture/TopLevel"));
        outerEntries.put("META-INF/jars/inner.jar", inner);
        Path outer = writeJar("both.jar", outerEntries);

        ScanResult result = ClassScanner.scan(List.of(outer), List.of());

        assertTrue(result.emittableTypes().containsAll(
                        List.of("ws/siri/fixture/TopLevel", "ws/siri/fixture/Nested")),
                "both classes should be emittable, got " + result.emittableTypes());
    }

    @Test
    void findsJarsSittingInADirectorySource() throws IOException {
        byte[] inner = jar(Map.of("ws/siri/fixture/Nested.class", classFile("ws/siri/fixture/Nested")));
        Path mods = Files.createDirectory(tempDir.resolve("mods"));
        Files.write(mods.resolve("mod.jar"), jar(Map.of("META-INF/jars/inner.jar", inner)));

        ScanResult result = ClassScanner.scan(List.of(mods), List.of());

        assertTrue(result.emittableTypes().contains("ws/siri/fixture/Nested"),
                "a jar in a dir source should be scanned, got " + result.emittableTypes());
    }

    /** A minimal public class, enough for ASM to read a name back out of. */
    private static byte[] classFile(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Path writeJar(String fileName, Map<String, byte[]> entries) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.write(path, jar(entries));
        return path;
    }
}
