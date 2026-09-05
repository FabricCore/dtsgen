package ws.siri.dtsgen.internal.emit;

import ws.siri.dtsgen.internal.GlobMatcher;
import ws.siri.dtsgen.internal.JvmNames;
import ws.siri.dtsgen.internal.model.JClass;
import ws.siri.dtsgen.internal.model.JMember;
import ws.siri.dtsgen.internal.scan.ScanResult;
import ws.siri.dtsgen.internal.sig.Sig;
import ws.siri.dtsgen.internal.sig.Signatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The resolved set of types to emit, plus the lookups the emitters share.
 *
 * <p>Deciding what is emitted happens once, here, so that both emitters agree: a reference to
 * a type that did not make the cut has to degrade to {@code any} identically in the per-class
 * modules and in the registry, or the two files contradict each other.
 */
public final class TypeUniverse {

    /** Erased keys of the public methods of Object, which an interface may redeclare freely. */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;");

    private final ScanResult scan;
    private final GlobMatcher opaque;
    private final Set<String> emitted = new LinkedHashSet<>();
    private final Map<String, List<JClass>> typesByFile = new TreeMap<>();
    private final Map<String, Integer> arity = new LinkedHashMap<>();
    private final Map<String, Boolean> functional = new HashMap<>();

    /**
     * @param scan     everything that was read
     * @param excluded types never emitted, whose references degrade to {@code any}
     * @param opaque   types emitted with no members, which severs the reference closure
     *                 through them
     */
    public TypeUniverse(ScanResult scan, GlobMatcher excluded, GlobMatcher opaque) {
        this.scan = scan;
        this.opaque = opaque;

        for (String internalName : scan.emittableTypes()) {
            JClass type = scan.find(internalName);
            if (type == null || !type.isPublic() || JvmNames.isAnonymousOrLocal(internalName)) {
                continue;
            }
            if (excluded.matches(JvmNames.dottedName(internalName))) continue;
            emitted.add(internalName);
        }
        dropOrphanedNestedTypes();
        groupIntoFiles();
        for (String internalName : emitted) {
            arity.put(internalName, formalsOf(scan.find(internalName)).size());
        }
    }

    /**
     * A nested type is only reachable if every class enclosing it is emitted too: it is
     * declared inside their namespaces, so a non-public link anywhere in the chain orphans it.
     * Removing a parent can orphan its children, so this runs to a fixed point.
     */
    private void dropOrphanedNestedTypes() {
        boolean changed = true;
        while (changed) {
            changed = emitted.removeIf(name -> {
                int nested = name.lastIndexOf('$');
                return nested >= 0 && !emitted.contains(name.substring(0, nested));
            });
        }
    }

    private void groupIntoFiles() {
        for (String internalName : emitted) {
            typesByFile.computeIfAbsent(JvmNames.topLevel(internalName), k -> new ArrayList<>())
                    .add(scan.find(internalName));
        }
        for (List<JClass> types : typesByFile.values()) {
            // An outer class must be declared before the namespace that extends it, and sorting
            // by internal name puts every enclosing type ahead of what it encloses.
            types.sort((a, b) -> a.internalName().compareTo(b.internalName()));
        }
    }

    /** Any scanned class, emitted or not, or null when it was never scanned. */
    public JClass type(String internalName) {
        return scan.find(internalName);
    }

    /** Internal names of every type being emitted, in scan order. */
    public Set<String> emittedTypes() {
        return Collections.unmodifiableSet(emitted);
    }

    /** Internal names of the top-level types, one file each, in path order. */
    public Set<String> fileNames() {
        return Collections.unmodifiableSet(typesByFile.keySet());
    }

    /** The top-level type and its emitted nested types, enclosing types first. */
    public List<JClass> typesInFile(String topLevelInternalName) {
        return typesByFile.getOrDefault(topLevelInternalName, List.of());
    }

    /** The emitted types declared directly inside {@code type}, excluding deeper nesting. */
    public List<JClass> directNestedTypes(JClass type) {
        List<JClass> children = new ArrayList<>();
        String prefix = type.internalName() + "$";
        for (JClass candidate : typesInFile(type.topLevelName())) {
            String name = candidate.internalName();
            if (name.startsWith(prefix) && name.indexOf('$', prefix.length()) < 0) {
                children.add(candidate);
            }
        }
        return children;
    }

    public boolean isEmitted(String internalName) {
        return emitted.contains(internalName);
    }

    /** True when the type is emitted as a bare name with no members. */
    public boolean isOpaque(JClass type) {
        return opaque.matches(JvmNames.binaryName(type.internalName()));
    }

    /** How many type parameters a type declares, so a raw reference can be padded. */
    public int arityOf(String internalName) {
        return arity.getOrDefault(internalName, 0);
    }

    /** The type parameters a type declares, empty when it is not generic. */
    public List<Sig.Formal> formalsOf(JClass type) {
        return Signatures.formalsOf(type);
    }

    public int scannedClassCount() {
        return scan.classes().size();
    }

    public int emittedTypeCount() {
        return emitted.size();
    }

    public int fileCount() {
        return typesByFile.size();
    }

    /**
     * True when a JS function can stand in for this type, which is what GraalJS does at a
     * parameter of a functional interface. An opaque type is emitted without members, so
     * nothing about it is callable.
     */
    public boolean isFunctionalInterface(String internalName) {
        // Not computeIfAbsent: the computation recurses into superinterfaces, which writes to
        // this same map.
        Boolean cached = functional.get(internalName);
        if (cached != null) return cached;
        boolean result = computeFunctional(internalName);
        functional.put(internalName, result);
        return result;
    }

    private boolean computeFunctional(String internalName) {
        JClass type = scan.find(internalName);
        if (type == null || !isEmitted(internalName) || isOpaque(type)) return false;
        // A class is never one, however many functional interfaces it happens to implement.
        if (!type.isInterface() || type.isAnnotation()) return false;
        List<JMember> abstracts = declaredAbstractMethods(type);
        if (!abstracts.isEmpty()) return abstracts.size() == 1;
        // UnaryOperator declares nothing of its own and is functional through Function. The
        // emitted interface extends it, so the call signature is inherited on the TS side too.
        for (String parent : type.interfaces()) {
            if (isFunctionalInterface(parent)) return true;
        }
        return false;
    }

    /**
     * The interface's own single abstract method, or null when it does not declare exactly one.
     *
     * <p>A method inherited rather than declared is deliberately not returned: the emitted
     * interface extends the one that declares it, so it already carries whatever that one got.
     */
    public JMember singleAbstractMethod(JClass type) {
        List<JMember> abstracts = declaredAbstractMethods(type);
        return abstracts.size() == 1 ? abstracts.get(0) : null;
    }

    /** The abstract methods this interface declares itself, the ones Object provides aside. */
    private static List<JMember> declaredAbstractMethods(JClass type) {
        // An annotation's elements are abstract too, and Java excludes annotations by fiat.
        if (!type.isInterface() || type.isAnnotation()) return List.of();
        List<JMember> abstracts = new ArrayList<>();
        for (JMember method : type.methods()) {
            if (!method.isPublic() || method.isSynthetic() || method.isBridge()) continue;
            if (method.isStatic() || !method.isAbstract()) continue;
            // Redeclaring a public method of Object -- as Comparator declares equals -- does not
            // count against the one abstract method, since every instance already has it.
            if (OBJECT_METHODS.contains(method.erasedKey())) continue;
            abstracts.add(method);
        }
        return abstracts;
    }

    /** Public fields reachable on this type but declared by a supertype that is not emitted. */
    public List<JMember> inheritedFields(JClass type) {
        return inheritedFromHiddenSupers(type, true);
    }

    /** Public methods reachable on this type but declared by a supertype that is not emitted. */
    public List<JMember> inheritedMethods(JClass type) {
        return inheritedFromHiddenSupers(type, false);
    }

    /**
     * A public class extending a package-private one still exposes that superclass's public
     * members at runtime, so dropping them would understate the API. Walks up until a supertype
     * is emitted (whose members the {@code extends} clause already carries), or the chain ends.
     */
    private List<JMember> inheritedFromHiddenSupers(JClass type, boolean fields) {
        List<JMember> inherited = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JMember declared : membersOf(type, fields)) seen.add(declared.erasedKey());

        String superName = type.superName();
        while (superName != null && !superName.equals("java/lang/Object")
                && !isEmitted(superName)) {
            JClass hidden = scan.find(superName);
            if (hidden == null) break;
            for (JMember member : membersOf(hidden, fields)) {
                if (!member.isPublic() || member.isSynthetic() || member.isBridge()
                        || member.isConstructor()) {
                    continue;
                }
                if (seen.add(member.erasedKey())) inherited.add(member);
            }
            superName = hidden.superName();
        }
        return inherited;
    }

    private static List<JMember> membersOf(JClass type, boolean fields) {
        return fields ? type.fields() : type.methods();
    }
}
