package ws.siri.dtsgen.internal;

/**
 * Conversions between the spellings of a Java type name.
 *
 * <p>A type has three names that matter here, all of which the generator moves between:
 * the JVM <em>internal</em> name {@code a/b/Outer$Inner} found in class files, the
 * <em>binary</em> name {@code a.b.Outer$Inner} that {@code Java.type} resolves, and the
 * <em>dotted</em> name {@code a.b.Outer.Inner} that a package glob and the emitted
 * TypeScript namespaces both use.
 */
public final class JvmNames {

    private JvmNames() {}

    /** The enclosing top-level type's internal name, or {@code internalName} if it is one. */
    public static String topLevel(String internalName) {
        int i = internalName.indexOf('$');
        return i < 0 ? internalName : internalName.substring(0, i);
    }

    /** The innermost segment alone: {@code a/b/Outer$Inner} to {@code Inner}. */
    public static String simpleName(String internalName) {
        String s = internalName.substring(internalName.lastIndexOf('/') + 1);
        int d = s.lastIndexOf('$');
        return d < 0 ? s : s.substring(d + 1);
    }

    /** The dotted package of the enclosing top-level type, empty for the default package. */
    public static String packageName(String internalName) {
        String top = topLevel(internalName);
        int i = top.lastIndexOf('/');
        return i < 0 ? "" : top.substring(0, i).replace('/', '.');
    }

    /** The name {@code Java.type} resolves: {@code a.b.Outer$Inner}. */
    public static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    /** The name a package glob and a TypeScript namespace path use: {@code a.b.Outer.Inner}. */
    public static String dottedName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    /**
     * The nested segments below {@code topLevel(internalName)}, dot-joined, or null when the
     * type is itself top-level: {@code a/b/Outer$Mid$In} to {@code Mid.In}.
     */
    public static String nestedSuffix(String internalName) {
        String top = topLevel(internalName);
        if (internalName.length() == top.length()) return null;
        return internalName.substring(top.length() + 1).replace('$', '.');
    }

    /**
     * True for a type no script can name: an anonymous class ({@code Outer$1}) or a local
     * class, neither of which is part of a usable API.
     */
    public static boolean isAnonymousOrLocal(String internalName) {
        int i = internalName.lastIndexOf('$');
        if (i < 0) return false;
        String tail = internalName.substring(i + 1);
        return tail.isEmpty() || Character.isDigit(tail.charAt(0));
    }
}
