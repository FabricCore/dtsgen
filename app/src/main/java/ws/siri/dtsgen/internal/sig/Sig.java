package ws.siri.dtsgen.internal.sig;

import java.util.ArrayList;
import java.util.List;

/**
 * Types and a recursive-descent parser for JVM descriptors and generic signatures
 * (JVMS 4.3.2 and 4.7.9.1).
 *
 * Both forms parse into the same AST so the emitter has one code path; a descriptor simply
 * yields no type arguments and no type variables.
 */
public final class Sig {

    public sealed interface Type permits Prim, Var, Arr, Cls {}

    /** One of B C D F I J S Z V. */
    public record Prim(char code) implements Type {}
    /** A type variable reference, e.g. {@code T} in {@code List<T>}. */
    public record Var(String name) implements Type {}
    public record Arr(Type element) implements Type {}

    /**
     * A class type. {@code internalName} joins any nested segments with '$', so
     * {@code Lcom/Foo<X>.Bar;} becomes {@code com/Foo$Bar}. {@code args} are the arguments of
     * the innermost segment, which is the only place they matter for emission.
     */
    public record Cls(String internalName, List<Arg> args) implements Type {}

    /** {@code kind} is '=' for an invariant argument, '+' extends, '-' super, '*' unbounded. */
    public record Arg(char kind, Type type) {}

    public record Formal(String name, List<Type> bounds) {}
    public record ClassSig(List<Formal> formals, Type superClass, List<Type> interfaces) {}
    public record MethodSig(List<Formal> formals, List<Type> params, Type returnType) {}

    private final String s;
    private int i;

    private Sig(String s) { this.s = s; this.i = 0; }

    // ---- entry points ----

    /** Parses a field descriptor or a generic field signature. */
    public static Type parseType(String sig) { return new Sig(sig).type(); }

    public static ClassSig parseClass(String sig) {
        Sig p = new Sig(sig);
        List<Formal> formals = p.formals();
        Type sup = p.type();
        List<Type> ifs = new ArrayList<>();
        while (p.i < p.s.length()) ifs.add(p.type());
        return new ClassSig(formals, sup, ifs);
    }

    public static MethodSig parseMethod(String sig) {
        Sig p = new Sig(sig);
        List<Formal> formals = p.formals();
        p.expect('(');
        List<Type> params = new ArrayList<>();
        while (p.peek() != ')') params.add(p.type());
        p.expect(')');
        Type ret = p.type();
        // Trailing ^throws signatures are not emitted; ignore whatever remains.
        return new MethodSig(formals, params, ret);
    }

    // ---- grammar ----

    private List<Formal> formals() {
        List<Formal> out = new ArrayList<>();
        if (peek() != '<') return out;
        expect('<');
        while (peek() != '>') {
            int start = i;
            while (s.charAt(i) != ':') i++;
            String name = s.substring(start, i);
            List<Type> bounds = new ArrayList<>();
            while (peek() == ':') {
                i++;
                // The class bound may be empty, leaving ':' or '>' immediately after.
                if (peek() != ':' && peek() != '>') bounds.add(type());
            }
            out.add(new Formal(name, bounds));
        }
        expect('>');
        return out;
    }

    private Type type() {
        char c = peek();
        switch (c) {
            case '[': i++; return new Arr(type());
            case 'T': {
                i++;
                int start = i;
                while (s.charAt(i) != ';') i++;
                String name = s.substring(start, i);
                i++;
                return new Var(name);
            }
            case 'L': return classType();
            default: i++; return new Prim(c);
        }
    }

    private Type classType() {
        expect('L');
        StringBuilder name = new StringBuilder();
        List<Arg> args = new ArrayList<>();
        while (true) {
            int start = i;
            while (";<.".indexOf(s.charAt(i)) < 0) i++;
            name.append(s, start, i);
            args = new ArrayList<>();
            if (peek() == '<') {
                i++;
                while (peek() != '>') args.add(arg());
                expect('>');
            }
            if (peek() == '.') {
                // A nested segment: Outer<..>.Inner<..>  ->  Outer$Inner
                i++;
                name.append('$');
                continue;
            }
            break;
        }
        expect(';');
        return new Cls(name.toString(), args);
    }

    private Arg arg() {
        char c = peek();
        if (c == '*') { i++; return new Arg('*', null); }
        if (c == '+' || c == '-') { i++; return new Arg(c, type()); }
        return new Arg('=', type());
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + i + " in " + s);
        }
        i++;
    }
}
