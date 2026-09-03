package ws.siri.dtsgen.internal.emit;

import ws.siri.dtsgen.internal.sig.Sig;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a parsed Java type as a TypeScript type, following GraalJS host-interop semantics
 * rather than Java's nominal ones: a Java String arrives in JS as a primitive string, a boxed
 * Integer as a number, and a Java array as a host object rather than a JS array.
 */
final class TypeMapper {

    /**
     * How the surrounding file names another type. The two emitters differ only here -- a
     * module refers to an imported alias, the registry to an inline {@code import(...)} type --
     * so the mapping itself is shared.
     */
    interface Naming {

        /** The TS reference for a type, or null if it is not emitted and should become {@code any}. */
        String ref(String internalName);

        /** How many type parameters that type declares, so a raw reference can be padded. */
        int arity(String internalName);
    }

    private static final Map<Character, String> PRIMITIVES = Map.of(
            'V', "void", 'Z', "boolean", 'C', "string",
            'B', "number", 'S', "number", 'I', "number", 'F', "number", 'D', "number",
            'J', "number");

    /**
     * Types Graal converts to a JS primitive on the way out, so declaring the Java class would
     * be actively wrong.
     */
    private static final Map<String, String> CONVERTED = Map.of(
            "java/lang/String", "string",
            "java/lang/CharSequence", "string",
            "java/lang/Character", "string",
            "java/lang/Boolean", "boolean",
            "java/lang/Integer", "number",
            "java/lang/Long", "number",
            "java/lang/Short", "number",
            "java/lang/Byte", "number",
            "java/lang/Float", "number",
            "java/lang/Double", "number");

    private final Naming naming;

    TypeMapper(Naming naming) {
        this.naming = naming;
    }

    /**
     * Renders one type.
     *
     * @param typeVars the type variables in scope at this position; a variable outside it
     *                 cannot be named and degrades to {@code any}
     */
    String render(Sig.Type type, Set<String> typeVars) {
        if (type instanceof Sig.Prim primitive) {
            return PRIMITIVES.getOrDefault(primitive.code(), "any");
        }
        if (type instanceof Sig.Var variable) {
            // A type variable that is not in scope here -- an inner class referring to its
            // outer class's parameters, which a TS namespace cannot carry -- degrades to any.
            return typeVars.contains(variable.name()) ? variable.name() : "any";
        }
        if (type instanceof Sig.Arr array) {
            return "JavaArray<" + render(array.element(), typeVars) + ">";
        }
        return renderClass((Sig.Cls) type, typeVars);
    }

    private String renderClass(Sig.Cls type, Set<String> typeVars) {
        String converted = CONVERTED.get(type.internalName());
        if (converted != null) return converted;
        if (type.internalName().equals("java/lang/Object")) return "any";

        String ref = naming.ref(type.internalName());
        if (ref == null) return "any";

        int arity = naming.arity(type.internalName());
        if (arity == 0) return ref;

        StringBuilder sb = new StringBuilder(ref).append('<');
        List<Sig.Arg> args = type.args();
        for (int i = 0; i < arity; i++) {
            if (i > 0) sb.append(", ");
            // A raw reference (from an erased descriptor) supplies no arguments, so pad with any
            // rather than emit a generic type with the wrong number of parameters.
            sb.append(i < args.size() ? renderArg(args.get(i), typeVars) : "any");
        }
        return sb.append('>').toString();
    }

    private String renderArg(Sig.Arg arg, Set<String> typeVars) {
        return switch (arg.kind()) {
            // TypeScript has no wildcards. `? extends T` is T; an unbounded or `? super T`
            // wildcard has no useful upper bound, so it becomes any.
            case '+', '=' -> render(arg.type(), typeVars);
            default -> "any";
        };
    }

    /**
     * Renders {@code <T extends Bound, U>}, or "" when the declaration is not generic.
     *
     * @param withDefaults appends {@code = any} to each parameter, which lets the type be
     *                     written without type arguments at all
     */
    String renderFormals(List<Sig.Formal> formals, Set<String> typeVars, boolean withDefaults) {
        if (formals.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < formals.size(); i++) {
            Sig.Formal formal = formals.get(i);
            if (i > 0) sb.append(", ");
            sb.append(formal.name());
            String bound = renderBound(formal.bounds(), typeVars);
            if (bound != null) sb.append(" extends ").append(bound);
            sb.append(withDefaults ? " = any" : "");
        }
        return sb.append('>').toString();
    }

    /** The bounds as a TS intersection, or null when none of them says anything useful. */
    private String renderBound(List<Sig.Type> bounds, Set<String> typeVars) {
        StringBuilder sb = new StringBuilder();
        for (Sig.Type bound : bounds) {
            String rendered = render(bound, typeVars);
            if (rendered.equals("any")) continue;
            if (!sb.isEmpty()) sb.append(" & ");
            sb.append(rendered);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
