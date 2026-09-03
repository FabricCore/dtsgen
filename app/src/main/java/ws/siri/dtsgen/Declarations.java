package ws.siri.dtsgen;

import ws.siri.dtsgen.internal.emit.ModuleEmitter;
import ws.siri.dtsgen.internal.emit.RegistryEmitter;
import ws.siri.dtsgen.internal.emit.TypeUniverse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The declarations a {@link DtsGenerator} resolved, ready to be rendered.
 *
 * <p>Nothing here is rendered until asked for, and no rendered text is retained, so a caller
 * that streams the output through {@link #writeTo(DeclarationSink)} holds one file in memory at
 * a time regardless of how many types were scanned. Rendering the same file twice simply does
 * the work twice; only {@link #registry()} is cached, because it is a single large file that
 * callers tend to both measure and write.
 *
 * <p>Instances are obtained from {@link DtsGenerator#generate()}.
 */
public final class Declarations {

    /** Name of the registry file, which is what a script project includes. */
    private static final String REGISTRY_FILE = "java.d.ts";
    /** Directory holding the per-class modules, below the output root. */
    private static final String MODULE_DIRECTORY = "full";

    private final TypeUniverse universe;
    private final ModuleEmitter moduleEmitter;
    private final RegistryEmitter registryEmitter;
    private final Set<String> registryTypes;
    private final List<String> unreadableClasses;
    private String registry;

    Declarations(TypeUniverse universe, ModuleEmitter moduleEmitter,
                 RegistryEmitter registryEmitter, Set<String> registryTypes,
                 List<String> unreadableClasses) {
        this.universe = universe;
        this.moduleEmitter = moduleEmitter;
        this.registryEmitter = registryEmitter;
        this.registryTypes = registryTypes;
        this.unreadableClasses = List.copyOf(unreadableClasses);
    }

    /**
     * Binary names of the top-level classes, one module file each, in path order. These are the
     * handles accepted by {@link #module(String)} and {@link #modulePath(String)}.
     */
    public List<String> moduleTypes() {
        List<String> types = new ArrayList<>(universe.fileCount());
        for (String internalName : universe.fileNames()) types.add(internalName.replace('/', '.'));
        return types;
    }

    /**
     * Where the module for a type belongs below the output root, such as
     * {@code full/net/minecraft/core/BlockPos.d.ts}.
     *
     * @param topLevelType a binary name from {@link #moduleTypes()}
     */
    public String modulePath(String topLevelType) {
        return MODULE_DIRECTORY + "/" + internalNameOf(topLevelType) + ".d.ts";
    }

    /**
     * Renders the module for one top-level class: its own declaration, its nested types, and
     * the imports they need.
     *
     * @param topLevelType a binary name from {@link #moduleTypes()}
     * @throws DtsGenerationException if no such type is being emitted
     */
    public String module(String topLevelType) {
        String internalName = internalNameOf(topLevelType);
        if (!universe.fileNames().contains(internalName)) {
            throw new DtsGenerationException("not an emitted top-level type: " + topLevelType);
        }
        return moduleEmitter.emit(internalName);
    }

    /** Where the registry belongs below the output root. */
    public String registryPath() {
        return REGISTRY_FILE;
    }

    /**
     * Renders the registry: the package-mirroring aliases, the {@code Java.type} lookup table,
     * and the {@code Java} object. Rendered once and cached.
     */
    public String registry() {
        if (registry == null) registry = registryEmitter.emit();
        return registry;
    }

    /**
     * Hands every file -- each module, then the registry -- to {@code sink} in output order.
     *
     * @throws IOException if the sink cannot store a file
     */
    public void writeTo(DeclarationSink sink) throws IOException {
        for (String type : moduleTypes()) {
            sink.accept(modulePath(type), module(type));
        }
        sink.accept(registryPath(), registry());
    }

    /**
     * Writes every file below {@code outputDirectory}, creating directories as needed and
     * overwriting what is already there.
     *
     * @throws IOException if a file cannot be written
     */
    public GenerationResult writeTo(Path outputDirectory) throws IOException {
        int files = 0;
        long moduleBytes = 0;
        for (String type : moduleTypes()) {
            moduleBytes += write(outputDirectory, modulePath(type), module(type));
            files++;
        }
        long registryBytes = write(outputDirectory, registryPath(), registry());
        return new GenerationResult(outputDirectory, files, moduleBytes, registryBytes);
    }

    /** How many class files were read, including classpath-only ones. */
    public int scannedClassCount() {
        return universe.scannedClassCount();
    }

    /** How many types are being emitted, nested types included. */
    public int emittedTypeCount() {
        return universe.emittedTypeCount();
    }

    /** How many module files the output has, one per top-level class. */
    public int moduleCount() {
        return universe.fileCount();
    }

    /** True when the registry covers only the types the scripts name. */
    public boolean isRegistryPruned() {
        return registryTypes != null;
    }

    /**
     * How many top-level classes the registry covers: every emitted one under
     * {@link GeneratorConfig.RegistryMode#FULL}, or the ones the scripts name when pruned.
     */
    public int registryTypeCount() {
        return registryTypes == null ? universe.fileCount() : registryTypes.size();
    }

    /**
     * Class files that could not be read, by internal name where it was recovered. Empty for a
     * clean run; never dropped silently, because a missing class quietly widens references to
     * it into {@code any}.
     */
    public List<String> unreadableClasses() {
        return unreadableClasses;
    }

    private long write(Path outputDirectory, String relativePath, String contents)
            throws IOException {
        Path file = outputDirectory;
        for (String segment : relativePath.split("/")) file = file.resolve(segment);
        Files.createDirectories(file.getParent());
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return bytes.length;
    }

    private static String internalNameOf(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
