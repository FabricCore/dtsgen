package ws.siri.dtsgen.internal.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Java classes a set of scripts actually names, which is what lets the registry be
 * pruned to them.
 *
 * <p>Only string literals are visible to a regex, so a computed name -- {@code
 * Java.type("net.minecraft." + x)} -- is not found and falls through to the untyped index at
 * runtime. That is the documented trade-off of the pruned registry, not a bug to fix here.
 */
public final class ScriptTypeScanner {

    /** Matches the only way a script can name a Java class: Java.type with a string literal. */
    private static final Pattern JAVA_TYPE =
            Pattern.compile("Java\\s*\\.\\s*type\\s*\\(\\s*[\"']([\\w.$]+)[\"']");

    private ScriptTypeScanner() {}

    /**
     * Walks every root for {@code .js} files and returns the binary names of the <em>top-level</em>
     * classes they name. Nested types are widened to their top-level class because the registry
     * is filtered by owning file, and a missing root is skipped rather than reported.
     *
     * @throws IOException if a script under an existing root cannot be read
     */
    public static Set<String> referencedTopLevelTypes(Collection<Path> scriptRoots)
            throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (Path root : scriptRoots) {
            if (!Files.exists(root)) continue;
            collectFrom(root, found);
        }
        Set<String> topLevel = new LinkedHashSet<>();
        for (String name : found) {
            int nested = name.indexOf('$');
            topLevel.add(nested < 0 ? name : name.substring(0, nested));
        }
        return topLevel;
    }

    private static void collectFrom(Path root, Set<String> found) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path script : walk.filter(p -> p.toString().endsWith(".js")).toList()) {
                Matcher m = JAVA_TYPE.matcher(Files.readString(script));
                while (m.find()) found.add(m.group(1));
            }
        }
    }
}
