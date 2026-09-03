package ws.siri.dtsgen;

import ws.siri.dtsgen.internal.json.ConfigJson;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * What to read, what to skip, and what shape the output takes. Immutable; build one with
 * {@link #builder()} or load one from a {@code dtsgen.jsonc} with {@link #fromJson(Path)}.
 *
 * <p>Every path is used as given, so a config built in code resolves relative paths against
 * the working directory. A config loaded from JSON has already had its paths resolved against
 * the config file's own directory, which is what lets a checked-in config be run from anywhere.
 *
 * <pre>{@code
 * GeneratorConfig config = GeneratorConfig.builder()
 *         .outputDirectory(Path.of("build/dts"))
 *         .addSource(Path.of("libs/minecraft-merged.jar"))
 *         .scope(GeneratorConfig.Scope.of(List.of(), List.of("sun.**", "jdk.**")))
 *         .build();
 * }</pre>
 */
public final class GeneratorConfig {

    /** How much JSDoc to attach to the emitted declarations. */
    public enum JsDocMode {
        /** No JSDoc at all. */
        NONE,
        /** {@code @deprecated} and {@code @throws} tags on the members that have them. */
        PARAMS;

        /** Parses the spelling used in the config file ({@code "none"}, {@code "params"}). */
        public static JsDocMode of(String value) {
            return parse(values(), value, "jsdoc");
        }
    }

    /** Which classes get a {@code Java.type} registry entry. */
    public enum RegistryMode {
        /** Every emitted class, so any of them can be named by a literal. */
        FULL,
        /**
         * Only the classes named by a literal somewhere under {@link #scriptRoots()}, which
         * cuts editor load time at the cost of regenerating when a script reaches for a new
         * class.
         */
        USED;

        /** Parses the spelling used in the config file ({@code "full"}, {@code "used"}). */
        public static RegistryMode of(String value) {
            return parse(values(), value, "registry");
        }
    }

    /**
     * Per-package-glob emission tiers. Globs use {@code **} to span dots and {@code *} within
     * one segment, matched against the dotted name: {@code net.minecraft.util.datafix.**}.
     *
     * @param opaqueGlobs  emitted with no members, which severs the transitive reference
     *                     closure through them while keeping the name resolvable
     * @param excludeGlobs never emitted; references to them degrade to {@code any}
     */
    public record Scope(List<String> opaqueGlobs, List<String> excludeGlobs) {

        public Scope {
            opaqueGlobs = List.copyOf(opaqueGlobs);
            excludeGlobs = List.copyOf(excludeGlobs);
        }

        /** A scope that hides nothing. */
        public static Scope none() {
            return new Scope(List.of(), List.of());
        }

        public static Scope of(Collection<String> opaqueGlobs, Collection<String> excludeGlobs) {
            return new Scope(List.copyOf(opaqueGlobs), List.copyOf(excludeGlobs));
        }
    }

    private final Path outputDirectory;
    private final List<Path> sources;
    private final List<Path> classpathOnly;
    private final List<Path> scriptRoots;
    private final JsDocMode jsDoc;
    private final RegistryMode registry;
    private final Scope scope;

    private GeneratorConfig(Builder b) {
        this.outputDirectory = b.outputDirectory;
        this.sources = List.copyOf(b.sources);
        this.classpathOnly = List.copyOf(b.classpathOnly);
        this.scriptRoots = List.copyOf(b.scriptRoots);
        this.jsDoc = b.jsDoc;
        this.registry = b.registry;
        this.scope = b.scope;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads a {@code dtsgen.jsonc}. Relative paths inside it resolve against the file's own
     * directory, and a leading {@code ~} expands to the user's home directory.
     *
     * @throws IOException            if the file cannot be read
     * @throws DtsGenerationException if it is not valid JSON, or names an unknown mode
     */
    public static GeneratorConfig fromJson(Path configFile) throws IOException {
        return ConfigJson.read(configFile);
    }

    /**
     * Reads the same JSON from an arbitrary source, resolving relative paths against
     * {@code baseDirectory}.
     *
     * @throws DtsGenerationException if the content is not valid JSON, or names an unknown mode
     */
    public static GeneratorConfig fromJson(Reader json, Path baseDirectory) {
        return ConfigJson.read(json, baseDirectory);
    }

    /** Where {@code full/} and the registry file are written. */
    public Path outputDirectory() { return outputDirectory; }

    /** Jars, {@code .jmod}s and directories whose public classes are emitted. */
    public List<Path> sources() { return sources; }

    /** Paths read to resolve supertypes and signatures, but never emitted. */
    public List<Path> classpathOnly() { return classpathOnly; }

    /** Script trees searched for {@code Java.type} literals under {@link RegistryMode#USED}. */
    public List<Path> scriptRoots() { return scriptRoots; }

    public JsDocMode jsDoc() { return jsDoc; }

    public RegistryMode registry() { return registry; }

    public Scope scope() { return scope; }

    /** A builder holding this configuration, for deriving a variant of it. */
    public Builder toBuilder() {
        return new Builder()
                .outputDirectory(outputDirectory)
                .sources(sources)
                .classpathOnly(classpathOnly)
                .scriptRoots(scriptRoots)
                .jsDoc(jsDoc)
                .registry(registry)
                .scope(scope);
    }

    /** Matches a JSON spelling against an enum's constants, case-insensitively. */
    private static <E extends Enum<E>> E parse(E[] values, String value, String key) {
        if (value != null) {
            for (E candidate : values) {
                if (candidate.name().equalsIgnoreCase(value)) return candidate;
            }
        }
        List<String> allowed = new ArrayList<>();
        for (E candidate : values) allowed.add(candidate.name().toLowerCase(Locale.ROOT));
        throw new DtsGenerationException(
                "unknown \"" + key + "\" value: " + value + " (expected one of " + allowed + ")");
    }

    /** Collects the parts of a {@link GeneratorConfig}; every setting has a usable default. */
    public static final class Builder {

        private Path outputDirectory = Path.of("dist");
        private final List<Path> sources = new ArrayList<>();
        private final List<Path> classpathOnly = new ArrayList<>();
        private final List<Path> scriptRoots = new ArrayList<>();
        private JsDocMode jsDoc = JsDocMode.PARAMS;
        private RegistryMode registry = RegistryMode.FULL;
        private Scope scope = Scope.none();

        private Builder() {}

        public Builder outputDirectory(Path directory) {
            this.outputDirectory = require(directory, "outputDirectory");
            return this;
        }

        /** Adds a jar, {@code .jmod} or directory whose public classes are emitted. */
        public Builder addSource(Path source) {
            sources.add(require(source, "source"));
            return this;
        }

        /** Replaces the sources with the given ones. */
        public Builder sources(Collection<Path> sources) {
            return replace(this.sources, sources, "sources");
        }

        /** Adds a path read to resolve supertypes and signatures, but never emitted. */
        public Builder addClasspathOnly(Path path) {
            classpathOnly.add(require(path, "classpathOnly entry"));
            return this;
        }

        public Builder classpathOnly(Collection<Path> paths) {
            return replace(this.classpathOnly, paths, "classpathOnly");
        }

        /** Adds a script tree to search under {@link RegistryMode#USED}. */
        public Builder addScriptRoot(Path root) {
            scriptRoots.add(require(root, "scriptRoot"));
            return this;
        }

        public Builder scriptRoots(Collection<Path> roots) {
            return replace(this.scriptRoots, roots, "scriptRoots");
        }

        public Builder jsDoc(JsDocMode mode) {
            this.jsDoc = require(mode, "jsDoc");
            return this;
        }

        public Builder registry(RegistryMode mode) {
            this.registry = require(mode, "registry");
            return this;
        }

        public Builder scope(Scope scope) {
            this.scope = require(scope, "scope");
            return this;
        }

        public GeneratorConfig build() {
            return new GeneratorConfig(this);
        }

        private Builder replace(List<Path> target, Collection<Path> values, String what) {
            require(values, what);
            target.clear();
            for (Path value : values) target.add(require(value, what + " entry"));
            return this;
        }

        private static <T> T require(T value, String what) {
            if (value == null) throw new IllegalArgumentException(what + " must not be null");
            return value;
        }
    }
}
