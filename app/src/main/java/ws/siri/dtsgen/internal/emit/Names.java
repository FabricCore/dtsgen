package ws.siri.dtsgen.internal.emit;

import java.util.Set;

/**
 * Identifier hygiene for the emitted TypeScript. A Java name is not always writable where the
 * declaration needs it, and the three positions have different rules: a property may be a
 * keyword but must be quoted if it is not an identifier, a parameter may not be a keyword at
 * all, and a type name must additionally not collide with anything else in its file.
 */
final class Names {

    /** Reserved in a position where TypeScript expects a binding name. */
    private static final Set<String> RESERVED = Set.of(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
            "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
            "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
            "implements", "interface", "let", "package", "private", "protected", "public",
            "static", "yield", "any", "boolean", "number", "string", "symbol", "declare",
            "namespace", "abstract", "arguments", "eval");

    private Names() {}

    /** True when the name can be written bare, without quoting. */
    static boolean isIdentifier(String name) {
        if (name.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$')) return false;
        }
        return true;
    }

    /** A property name, quoted only when it cannot be written bare. Keywords are fine here. */
    static String member(String name) {
        return isIdentifier(name) ? name : "\"" + name.replace("\"", "\\\"") + "\"";
    }

    /**
     * A parameter name, which unlike a property name cannot be a reserved word.
     *
     * @param name  the real name, or null when none was recovered
     * @param index position in the parameter list, used to build a positional fallback
     */
    static String param(String name, int index) {
        if (name == null || !isIdentifier(name) || RESERVED.contains(name)) return "a" + index;
        return name;
    }

    /**
     * A type or alias name, made unique against {@code used} and added to it. A name that
     * cannot stand alone is prefixed rather than replaced, so the alias still reads as the
     * type it refers to.
     */
    static String unique(String base, Set<String> used) {
        String safe = isIdentifier(base) && !RESERVED.contains(base) ? base : "_" + base;
        String candidate = safe;
        int n = 1;
        while (!used.add(candidate)) candidate = safe + (++n);
        return candidate;
    }
}
