package ws.siri.dtsgen.internal.sig;

import ws.siri.dtsgen.internal.model.JClass;
import ws.siri.dtsgen.internal.model.JMember;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the signature of a scanned member or class, preferring the generic form and falling
 * back to the erased one.
 *
 * <p>Every fallback here is deliberate: a Signature attribute can be malformed or use a
 * construct the parser does not model, and the erased descriptor is always parseable. Losing
 * the type arguments for one member is a far smaller cost than failing the whole run, so a
 * parse failure degrades rather than propagates.
 */
public final class Signatures {

    private Signatures() {}

    /** The type of a field. */
    public static Sig.Type fieldType(JMember field) {
        try {
            return Sig.parseType(field.signatureOrDescriptor());
        } catch (RuntimeException e) {
            return Sig.parseType(field.descriptor());
        }
    }

    /** The formal type parameters, parameters and return type of a method or constructor. */
    public static Sig.MethodSig method(JMember method) {
        try {
            return Sig.parseMethod(method.signatureOrDescriptor());
        } catch (RuntimeException e) {
            return Sig.parseMethod(method.descriptor());
        }
    }

    /**
     * The formal type parameters, superclass and interfaces of a class. The erased fallback
     * carries no type parameters and no type arguments, which is exactly how a class compiled
     * without generics reads.
     */
    public static Sig.ClassSig classSignature(JClass type) {
        if (type.signature() != null) {
            try {
                return Sig.parseClass(type.signature());
            } catch (RuntimeException ignored) {
                // fall through to the erased form
            }
        }
        List<Sig.Type> interfaces = new ArrayList<>();
        for (String i : type.interfaces()) interfaces.add(new Sig.Cls(i, List.of()));
        String superName = type.superName() == null ? "java/lang/Object" : type.superName();
        return new Sig.ClassSig(List.of(), new Sig.Cls(superName, List.of()), interfaces);
    }

    /** The type parameters a class declares, empty when it is not generic. */
    public static List<Sig.Formal> formalsOf(JClass type) {
        return type == null ? List.of() : classSignature(type).formals();
    }

    /** The names of the given type parameters, in declaration order. */
    public static Set<String> namesOf(List<Sig.Formal> formals) {
        Set<String> names = new LinkedHashSet<>();
        for (Sig.Formal f : formals) names.add(f.name());
        return names;
    }
}
