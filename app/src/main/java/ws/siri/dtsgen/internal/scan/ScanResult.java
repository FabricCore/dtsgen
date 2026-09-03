package ws.siri.dtsgen.internal.scan;

import ws.siri.dtsgen.internal.model.JClass;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a scan found.
 *
 * @param classes           every class read, by internal name, including classpath-only ones
 *                          that exist purely to resolve supertypes and signatures; iteration
 *                          order is scan order
 * @param emittableTypes    internal names that came from a source, so are candidates for
 *                          emission; always a subset of {@code classes}
 * @param unreadableClasses class files ASM could not read, reported rather than dropped
 */
public record ScanResult(Map<String, JClass> classes, Set<String> emittableTypes,
                         List<String> unreadableClasses) {

    public ScanResult {
        // Insertion-ordered copies: scan order decides the order of the emitted declarations,
        // so a hash-ordered copy would reshuffle the output from run to run.
        classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        emittableTypes = Collections.unmodifiableSet(new LinkedHashSet<>(emittableTypes));
        unreadableClasses = List.copyOf(unreadableClasses);
    }

    /** The class with this internal name, or null when it was never scanned. */
    public JClass find(String internalName) {
        return classes.get(internalName);
    }
}
