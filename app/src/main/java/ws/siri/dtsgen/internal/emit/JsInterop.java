package ws.siri.dtsgen.internal.emit;

import java.util.List;
import java.util.Map;

/**
 * Where GraalJS treats a JS value and a Java type as the same thing, which neither the Java
 * signature nor its TypeScript rendering says on its own.
 *
 * <p>The two directions are not mirror images. Going in, Truffle converts a guest value to a
 * short list of Java types structurally -- a JS array to a List, a JS object to a Map, a JS Date
 * to an Instant -- so a parameter of one of those accepts either form. The conversion is a live
 * view rather than a copy, and it recurses through type arguments, so a JS array of functions
 * arrives as a {@code List<Runnable>}. Coming out, only a List and a Java array are array-like;
 * JS gives those two {@code Array.prototype}, so their declarations carry members no Java
 * signature mentions.
 *
 * <p>Left out on purpose: a guest object also satisfies any interface at all, through a dynamic
 * proxy. So {@code Set<String>} does accept a JS array -- and then throws on the first call,
 * because an array has no {@code contains}. Typing that would cost every interface its meaning
 * in exchange for a conversion that does not work.
 */
final class JsInterop {

    private JsInterop() {}

    /**
     * What else a parameter of each type accepts, as a template over the type arguments as they
     * render in parameter position: {@code %1$s} the first, {@code %2$s} the second.
     */
    private static final Map<String, String> PARAMETER_FORMS = Map.ofEntries(
            Map.entry("java/util/List", "readonly %1$s[]"),
            Map.entry("java/util/Collection", "readonly %1$s[]"),
            // Any JS iterable converts here, a Set or a generator included, but writing
            // `Iterable<T>` would name the Java interface of that name inside most modules.
            Map.entry("java/lang/Iterable", "readonly %1$s[]"),
            Map.entry("java/util/Map$Entry", "readonly [%1$s, %2$s]"),
            Map.entry("java/util/Map", "{ readonly [key: string]: %2$s }"),
            // A JS Date carries an instant and a zone, so it satisfies every shape of both.
            Map.entry("java/util/Date", "JsDate"),
            Map.entry("java/time/Instant", "JsDate"),
            Map.entry("java/time/LocalDate", "JsDate"),
            Map.entry("java/time/LocalDateTime", "JsDate"),
            Map.entry("java/time/LocalTime", "JsDate"),
            Map.entry("java/time/ZonedDateTime", "JsDate"),
            Map.entry("java/time/ZoneId", "JsDate"));

    /** A JS object has string keys, so it is only a Map that is keyed by String. */
    private static final String STRING_KEYED = "java/util/Map";

    /**
     * The JS form a parameter of this type also accepts, or null when it has none.
     *
     * @param firstArg  the first type argument, already rendered for parameter position
     * @param secondArg the second, or {@code any} where the type has fewer
     */
    static String parameterAlternative(String internalName, String firstArg, String secondArg) {
        String form = PARAMETER_FORMS.get(internalName);
        if (form == null) return null;
        if (internalName.equals(STRING_KEYED) && !firstArg.equals("string")) return null;
        return form.formatted(firstArg, secondArg);
    }

    /** Supertypes that carry the JS members a type gains on the way out. */
    private static final Map<String, List<String>> HOST_SUPERTYPES = Map.of(
            "java/util/List", List.of("JsArrayMembers<%1$s>", "JsArrayResizing<%1$s>"));

    /**
     * Members to declare on the type itself. Every entry here is an interface, so they are
     * written into an interface body; a class would need the merged interface instead.
     */
    private static final Map<String, List<String>> HOST_MEMBERS = Map.of(
            "java/util/List", List.of("length: number;", "[index: number]: %1$s;"),
            // Reached by List, Set and everything else that iterates, through their extends
            // clauses; a Java Iterator is separately iterable without being an Iterable. It has
            // to be declared in one place only: a subtype inheriting two spellings of
            // [Symbol.iterator] resolves the conflict the wrong way and stops being an array.
            "java/lang/Iterable", List.of("[Symbol.iterator](): JsIterator<%1$s>;"),
            "java/util/Iterator", List.of("[Symbol.iterator](): JsIterator<%1$s>;"));

    static List<String> hostSupertypes(String internalName, List<String> formalNames) {
        return fill(HOST_SUPERTYPES.get(internalName), formalNames);
    }

    static List<String> hostMembers(String internalName, List<String> formalNames) {
        return fill(HOST_MEMBERS.get(internalName), formalNames);
    }

    private static List<String> fill(List<String> forms, List<String> formalNames) {
        if (forms == null) return List.of();
        Object[] args = {formalName(formalNames, 0), formalName(formalNames, 1)};
        return forms.stream().map(form -> form.formatted(args)).toList();
    }

    /** A type read from an erased descriptor has no formals to name; any is the honest answer. */
    private static String formalName(List<String> formalNames, int index) {
        return index < formalNames.size() ? formalNames.get(index) : "any";
    }
}
