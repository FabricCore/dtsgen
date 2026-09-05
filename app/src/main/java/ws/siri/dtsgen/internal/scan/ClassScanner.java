package ws.siri.dtsgen.internal.scan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import ws.siri.dtsgen.internal.model.JClass;
import ws.siri.dtsgen.internal.model.JMember;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads class files with ASM without loading them.
 *
 * <p>Not loading is the point: the classes being described generally cannot be loaded in a
 * plain JVM without their full classpath, and loading them would run static initializers.
 * Reading bytes has neither problem, and works equally on a jar, a {@code .jmod} (which is
 * also a zip) and a directory of loose class files.
 *
 * <p>Archives are recursed into, because a mod jar is often only a container: fabric-api ships
 * no classes of its own, just one jar per module under {@code META-INF/jars/}.
 */
public final class ClassScanner {

    private static final int ASM_API = Opcodes.ASM9;

    /** Real nesting is one level; the cap only stops a self-containing archive. */
    private static final int MAX_ARCHIVE_DEPTH = 8;

    private final Map<String, JClass> classes = new LinkedHashMap<>();
    private final Set<String> emittable = new LinkedHashSet<>();
    private final List<String> unreadable = new ArrayList<>();

    private ClassScanner() {}

    /**
     * Scans every source, then every classpath-only path.
     *
     * @param sources       paths whose classes are candidates for emission
     * @param classpathOnly paths read only to resolve supertypes and signatures
     * @throws IOException if a path cannot be opened or walked
     */
    public static ScanResult scan(Collection<Path> sources, Collection<Path> classpathOnly)
            throws IOException {
        ClassScanner scanner = new ClassScanner();
        for (Path source : sources) scanner.scanPath(source, true);
        for (Path path : classpathOnly) scanner.scanPath(path, false);
        return new ScanResult(scanner.classes, scanner.emittable, scanner.unreadable);
    }

    private void scanPath(Path path, boolean emit) throws IOException {
        if (Files.isDirectory(path)) {
            scanDirectory(path, emit);
        } else {
            scanArchive(path, emit);
        }
    }

    /**
     * Reads the loose class files under a directory, and any jar sitting in it.
     *
     * <p>Taking the jars too makes a whole mods folder a single source entry. The directory is
     * taken wholesale, so whatever else lives there is scanned as well; {@code scope.exclude}
     * is what trims that back.
     */
    private void scanDirectory(Path root, boolean emit) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (isArchiveFile(file)) {
                    scanArchive(file, emit);
                } else if (isClassFile(file)) {
                    try (InputStream in = Files.newInputStream(file)) {
                        read(in.readAllBytes(), emit);
                    }
                }
            }
        }
    }

    private void scanArchive(Path archive, boolean emit) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".jar")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        scanNested(in.readAllBytes(), archive + "!" + name, emit, 1);
                    }
                } else if (isClassEntry(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        read(in.readAllBytes(), emit);
                    }
                }
            }
        }
    }

    /**
     * Scans an archive shipped inside another one.
     *
     * <p>Fabric mods package their modules this way: the fabric-api jar holds no classes of its
     * own, only one jar per module under {@code META-INF/jars/}. Ignoring those would make such
     * a source contribute nothing at all.
     *
     * @param bytes the nested archive, already extracted from its container
     * @param label container-qualified name, used only to report an archive that failed to read
     * @param depth how many archives deep this one sits, counting from the file on disk
     */
    private void scanNested(byte[] bytes, String label, boolean emit, int depth) {
        if (depth > MAX_ARCHIVE_DEPTH) {
            unreadable.add(label + ": nested more than " + MAX_ARCHIVE_DEPTH + " archives deep");
            return;
        }
        // Buffered rather than streamed from the parent: a ZipInputStream wrapped around another
        // closes it on the way out, which would end the enclosing walk after the first nested jar.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // readAllBytes stops at the end of the current entry, not the whole archive.
                if (name.endsWith(".jar")) {
                    scanNested(zip.readAllBytes(), label + "!" + name, emit, depth + 1);
                } else if (isClassEntry(name)) {
                    read(zip.readAllBytes(), emit);
                }
            }
        } catch (IOException ex) {
            // A container is still worth the classes its other entries yielded.
            unreadable.add(label + ": " + ex);
        }
    }

    private static boolean isClassEntry(String name) {
        // META-INF holds versioned duplicates and module descriptors, not API.
        return name.endsWith(".class") && !name.startsWith("META-INF/");
    }

    private static boolean isClassFile(Path path) {
        return path.toString().endsWith(".class");
    }

    private static boolean isArchiveFile(Path path) {
        return path.toString().endsWith(".jar");
    }

    private void read(byte[] bytes, boolean emit) {
        JClass.Builder builder = JClass.builder();
        try {
            // SKIP_CODE would discard the LocalVariableTable, which is the only source of real
            // parameter names for methods not compiled with -parameters.
            new ClassReader(bytes).accept(new ClassFileVisitor(builder), ClassReader.SKIP_FRAMES);
        } catch (RuntimeException ex) {
            String name = builder.internalName();
            unreadable.add(name != null ? name : "<unnamed>: " + ex);
            return;
        }
        if (builder.internalName() == null) return;
        JClass type = builder.build();
        classes.put(type.internalName(), type);
        if (emit) emittable.add(type.internalName());
    }

    /** Copies the parts of a class file the emitters need into a {@link JClass.Builder}. */
    private static final class ClassFileVisitor extends ClassVisitor {

        private final JClass.Builder builder;

        ClassFileVisitor(JClass.Builder builder) {
            super(ASM_API);
            this.builder = builder;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            builder.header(name, access, signature, superName,
                    interfaces == null ? List.of() : List.of(interfaces));
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // Only the entry describing this class itself is relevant, and its access flags are
            // authoritative for a nested type -- the top-level access_flags lose the distinction.
            if (name.equals(builder.internalName())) {
                builder.nestedAccess(access);
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            builder.addField(new JMember(access, name, descriptor, signature,
                    isDeprecated(access), List.of(), List.of()));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            return new ParameterNameCollector(access, name, descriptor, signature, exceptions);
        }

        private static boolean isDeprecated(int access) {
            return (access & Opcodes.ACC_DEPRECATED) != 0;
        }

        /**
         * Visits one method, recovering its parameter names on the way, and adds the finished
         * member to the enclosing builder.
         */
        private final class ParameterNameCollector extends MethodVisitor {

            private final int access;
            private final String name;
            private final String descriptor;
            private final String signature;
            private final List<String> exceptions;
            private final List<String> declared = new ArrayList<>();
            private final Map<Integer, String> localsBySlot = new HashMap<>();

            ParameterNameCollector(int access, String name, String descriptor, String signature,
                                   String[] exceptions) {
                super(ASM_API);
                this.access = access;
                this.name = name;
                this.descriptor = descriptor;
                this.signature = signature;
                this.exceptions = exceptions == null ? List.of() : List.of(exceptions);
            }

            @Override
            public void visitParameter(String parameterName, int parameterAccess) {
                // A MethodParameters entry may carry no name at all, which ASM reports as
                // null; those fall back to the LocalVariableTable or a positional name.
                declared.add(parameterName == null ? "" : parameterName);
            }

            @Override
            public void visitLocalVariable(String localName, String desc, String sig,
                                           Label start, Label end, int index) {
                // Parameters occupy the lowest slots and are emitted first, so the first entry
                // seen for a slot is the parameter rather than a later local reusing it.
                localsBySlot.putIfAbsent(index, localName);
            }

            @Override
            public void visitEnd() {
                builder.addMethod(new JMember(access, name, descriptor, signature,
                        isDeprecated(access), resolveParameterNames(), exceptions));
            }

            /**
             * Prefers MethodParameters, falls back to the LocalVariableTable, and gives up
             * (returning nothing) rather than mixing real names with positional ones.
             */
            private List<String> resolveParameterNames() {
                Type[] parameters = Type.getArgumentTypes(descriptor);
                if (declared.size() == parameters.length
                        && declared.stream().anyMatch(n -> !n.isEmpty())) {
                    return declared;
                }
                List<String> names = new ArrayList<>(parameters.length);
                // Slot 0 holds `this` on an instance method; a long or double takes two slots.
                int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                for (Type parameter : parameters) {
                    String name = localsBySlot.get(slot);
                    if (name == null) return List.of();
                    names.add(name);
                    slot += parameter.getSize();
                }
                return names;
            }
        }
    }
}
