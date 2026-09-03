package ws.siri.dtsgen;

import ws.siri.dtsgen.internal.GlobMatcher;
import ws.siri.dtsgen.internal.emit.ModuleEmitter;
import ws.siri.dtsgen.internal.emit.RegistryEmitter;
import ws.siri.dtsgen.internal.emit.TypeUniverse;
import ws.siri.dtsgen.internal.scan.ClassScanner;
import ws.siri.dtsgen.internal.scan.ScanResult;
import ws.siri.dtsgen.internal.scan.ScriptTypeScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * Generates TypeScript declarations from Java bytecode.
 *
 * <p>The classes described are read, never loaded, so a jar needs neither a complete classpath
 * nor a JVM that could run it. Point the generator at jars, {@code .jmod}s or directories of
 * class files and it emits one ES module per top-level class plus a registry file that makes
 * {@code Java.type("some.Class")} infer in a plain JS project.
 *
 * <pre>{@code
 * GeneratorConfig config = GeneratorConfig.fromJson(Path.of("dtsgen.jsonc"));
 * Declarations declarations = new DtsGenerator(config).generate();
 * GenerationResult result = declarations.writeTo(config.outputDirectory());
 * }</pre>
 *
 * <p>A caller that wants the text rather than files can render it directly with
 * {@link Declarations#module(String)} and {@link Declarations#registry()}, or stream every file
 * to a {@link DeclarationSink}.
 *
 * <p>Instances are immutable and hold no scan state, so one can be reused; each
 * {@link #generate()} call rescans.
 */
public final class DtsGenerator {

    private final GeneratorConfig config;

    public DtsGenerator(GeneratorConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    /** The configuration this generator was built with. */
    public GeneratorConfig config() {
        return config;
    }

    /**
     * Scans every configured source and resolves what to emit. This is the expensive step;
     * rendering the result afterwards is comparatively cheap.
     *
     * @throws IOException            if a source cannot be read
     * @throws DtsGenerationException if a configured path does not exist
     */
    public Declarations generate() throws IOException {
        requireExists(config.sources(), "source");
        requireExists(config.classpathOnly(), "classpathOnly path");

        ScanResult scan = ClassScanner.scan(config.sources(), config.classpathOnly());
        GeneratorConfig.Scope scope = config.scope();
        TypeUniverse universe = new TypeUniverse(scan,
                GlobMatcher.of(scope.excludeGlobs()),
                GlobMatcher.of(scope.opaqueGlobs()));

        Set<String> registryTypes = resolveRegistryTypes();
        return new Declarations(universe,
                new ModuleEmitter(universe, config.jsDoc() != GeneratorConfig.JsDocMode.NONE),
                new RegistryEmitter(universe, registryTypes),
                registryTypes,
                scan.unreadableClasses());
    }

    /**
     * The top-level classes the registry is limited to, or null to include every emitted class.
     */
    private Set<String> resolveRegistryTypes() throws IOException {
        if (config.registry() != GeneratorConfig.RegistryMode.USED) return null;
        return ScriptTypeScanner.referencedTopLevelTypes(config.scriptRoots());
    }

    private static void requireExists(Collection<Path> paths, String what) {
        for (Path path : paths) {
            if (!Files.exists(path)) {
                throw new DtsGenerationException(what + " not found: " + path);
            }
        }
    }
}
