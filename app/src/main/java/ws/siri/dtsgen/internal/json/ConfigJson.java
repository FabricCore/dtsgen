package ws.siri.dtsgen.internal.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import ws.siri.dtsgen.DtsGenerationException;
import ws.siri.dtsgen.GeneratorConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-disk shape of {@code dtsgen.jsonc}, and the only place that knows it.
 *
 * <p>It is deliberately separate from {@link GeneratorConfig}: this class is mutable, nullable
 * and reflectively populated because that is what a JSON binding needs, and keeping those
 * properties out of the public configuration type is what lets that type be immutable and
 * validated. Paths are resolved here too, so nothing downstream carries a base directory.
 *
 * <p>Unknown keys are ignored, which keeps a config written for a newer or older version of
 * the generator loadable.
 */
public final class ConfigJson {

    /** An environment variable reference in a path, as {@code ${NAME}}. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** One entry of {@code sources}; {@code jar} and {@code dir} are read the same way. */
    private static final class SourceJson {
        private String jar;
        private String dir;
    }

    /** The {@code scope} object. */
    private static final class ScopeJson {
        private List<String> opaque;
        private List<String> exclude;
    }

    private String out;
    private List<SourceJson> sources;
    private List<String> classpathOnly;
    private String jsdoc;
    private String registry;
    private List<String> scriptRoots;
    private ScopeJson scope;

    private ConfigJson() {}

    /**
     * Reads a config file, resolving its paths against the file's own directory.
     *
     * @throws IOException            if the file cannot be read
     * @throws DtsGenerationException if it is not valid JSON, or names an unknown mode
     */
    public static GeneratorConfig read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return read(reader, file.toAbsolutePath().getParent());
        }
    }

    /**
     * Reads the same JSON from anywhere, resolving relative paths against {@code baseDirectory}.
     *
     * @throws DtsGenerationException if the content is not valid JSON, or names an unknown mode
     */
    public static GeneratorConfig read(Reader json, Path baseDirectory) {
        Gson gson = new GsonBuilder().setStrictness(Strictness.LENIENT).create();
        ConfigJson parsed;
        try {
            parsed = gson.fromJson(json, ConfigJson.class);
        } catch (JsonParseException e) {
            throw new DtsGenerationException("malformed config JSON: " + e.getMessage(), e);
        }
        // An empty file parses to null; every setting then falls back to its default.
        return (parsed == null ? new ConfigJson() : parsed).toConfig(baseDirectory);
    }

    private GeneratorConfig toConfig(Path baseDirectory) {
        GeneratorConfig.Builder builder = GeneratorConfig.builder();
        if (out != null) builder.outputDirectory(resolve(baseDirectory, out));
        for (SourceJson source : orEmpty(sources)) {
            builder.addSource(resolve(baseDirectory, pathOf(source)));
        }
        for (String path : orEmpty(classpathOnly)) {
            builder.addClasspathOnly(resolve(baseDirectory, path));
        }
        for (String root : orEmpty(scriptRoots)) {
            builder.addScriptRoot(resolve(baseDirectory, root));
        }
        if (jsdoc != null) builder.jsDoc(GeneratorConfig.JsDocMode.of(jsdoc));
        if (registry != null) builder.registry(GeneratorConfig.RegistryMode.of(registry));
        if (scope != null) {
            builder.scope(GeneratorConfig.Scope.of(orEmpty(scope.opaque), orEmpty(scope.exclude)));
        }
        return builder.build();
    }

    private static String pathOf(SourceJson source) {
        String path = source.jar != null ? source.jar : source.dir;
        if (path == null) {
            throw new DtsGenerationException("a \"sources\" entry must set \"jar\" or \"dir\"");
        }
        return path;
    }

    /**
     * Resolves one path from the config: {@code ${VAR}} is substituted from the environment, a
     * leading {@code ~} expands to the user's home directory, and what remains is resolved
     * against the config file's own directory unless it is already absolute.
     */
    private static Path resolve(Path baseDirectory, String rawPath) {
        String expanded = expandVariables(rawPath);
        if (expanded.startsWith("~")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        Path path = Paths.get(expanded);
        if (path.isAbsolute()) return path;
        return baseDirectory == null ? path : baseDirectory.resolve(path).normalize();
    }

    /**
     * Substitutes every {@code ${VAR}} from the environment, so a config can name a location
     * that differs per machine -- {@code ${JAVA_HOME}/jmods/java.base.jmod} -- and still be
     * checked in.
     *
     * <p>An unset variable is an error rather than an empty string: silently resolving to a
     * path like {@code /jmods/java.base.jmod} would fail later with nothing pointing at the
     * cause.
     */
    private static String expandVariables(String rawPath) {
        Matcher matcher = VARIABLE.matcher(rawPath);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = System.getenv(name);
            if (value == null) {
                throw new DtsGenerationException(
                        "environment variable " + name + " is not set, used by: " + rawPath);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        return matcher.appendTail(expanded).toString();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }
}
