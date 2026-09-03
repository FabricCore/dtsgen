package ws.siri.dtsgen.internal.emit;

import ws.siri.dtsgen.internal.JvmNames;
import ws.siri.dtsgen.internal.model.JClass;
import ws.siri.dtsgen.internal.model.JMember;
import ws.siri.dtsgen.internal.sig.Sig;
import ws.siri.dtsgen.internal.sig.Signatures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Emits one ES module per top-level class, with its nested types folded into the same file.
 *
 * <p>One file per top-level class rather than per package keeps a reference from one class to
 * another an ordinary module import, which is what lets an editor load only the types a script
 * actually touches.
 */
public final class ModuleEmitter {

    /** Which declaration a member list is being written into, which decides its keywords. */
    private enum MemberStyle {
        /** {@code declare class}: instance and static members, statics prefixed. */
        CLASS,
        /** {@code interface}: instance members only, no keywords. */
        INTERFACE,
        /** {@code namespace}: statics only, as {@code const} and {@code function}. */
        NAMESPACE
    }

    private final TypeUniverse universe;
    private final boolean jsDoc;

    /**
     * @param jsDoc whether to attach {@code @deprecated} and {@code @throws} tags
     */
    public ModuleEmitter(TypeUniverse universe, boolean jsDoc) {
        this.universe = universe;
        this.jsDoc = jsDoc;
    }

    /**
     * Renders the module for one top-level class, imports included.
     *
     * @param topLevelInternalName a name from {@link TypeUniverse#fileNames()}
     */
    public String emit(String topLevelInternalName) {
        ModuleNaming naming = new ModuleNaming(topLevelInternalName);
        TypeMapper mapper = new TypeMapper(naming);

        // The body is rendered first: it is what discovers the imports, by asking the naming
        // context for a reference to every type it mentions.
        StringBuilder body = new StringBuilder();
        emitType(universe.type(topLevelInternalName), body, "", true, mapper);

        return naming.renderImports().append(body).toString();
    }

    /**
     * Names types the way a module refers to them: its own types by their local name, everything
     * else through an import alias allocated on first use.
     */
    private final class ModuleNaming implements TypeMapper.Naming {

        private final String topInternal;
        private final String topSimple;
        /** Top-level internal name -> the alias this file imports it under. */
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final Set<String> used = new LinkedHashSet<>();

        ModuleNaming(String topInternal) {
            this.topInternal = topInternal;
            this.topSimple = JvmNames.simpleName(topInternal);
            // Single capitals are the usual Java type-variable names; keeping them out of the
            // alias pool stops an imported class shadowing a method's own <T>.
            for (char ch = 'A'; ch <= 'Z'; ch++) used.add(String.valueOf(ch));
            used.add(topSimple);
        }

        @Override
        public String ref(String internalName) {
            if (!universe.isEmitted(internalName)) return null;
            String top = JvmNames.topLevel(internalName);
            String base = top.equals(topInternal)
                    ? topSimple
                    : aliases.computeIfAbsent(top, t -> Names.unique(JvmNames.simpleName(t), used));
            String nested = JvmNames.nestedSuffix(internalName);
            return nested == null ? base : base + "." + nested;
        }

        @Override
        public int arity(String internalName) {
            return universe.arityOf(internalName);
        }

        /** The import block, one line per alias, ordered by alias so the output is stable. */
        StringBuilder renderImports() {
            StringBuilder out = new StringBuilder();
            Map<String, String> targetsByAlias = new TreeMap<>();
            for (var entry : aliases.entrySet()) targetsByAlias.put(entry.getValue(), entry.getKey());

            for (var entry : targetsByAlias.entrySet()) {
                String alias = entry.getKey();
                String target = entry.getValue();
                String simple = JvmNames.simpleName(target);
                out.append("import type { ").append(simple);
                if (!simple.equals(alias)) out.append(" as ").append(alias);
                out.append(" } from \"").append(relativePath(topInternal, target)).append("\";\n");
            }
            if (!aliases.isEmpty()) out.append('\n');
            return out;
        }
    }

    // ---- declarations ----

    /**
     * Emits one type and, recursively, the types nested inside it.
     *
     * @param indent    leading whitespace for this nesting depth
     * @param topLevel  true for the file's own class, which is the only exported declaration
     */
    private void emitType(JClass type, StringBuilder sb, String indent, boolean topLevel,
                          TypeMapper mapper) {
        List<Sig.Formal> formals = universe.formalsOf(type);
        Set<String> classVars = Signatures.namesOf(formals);
        String params = mapper.renderFormals(formals, classVars, false);
        String name = type.simpleName();
        List<JClass> nested = universe.directNestedTypes(type);
        boolean opaque = universe.isOpaque(type);

        Sig.ClassSig signature = Signatures.classSignature(type);
        List<String> interfaces = opaque
                ? List.of()
                : renderedSupertypes(signature.interfaces(), classVars, mapper);

        sb.append(doc(type.isDeprecated(), List.of(), indent));
        if (type.isInterface()) {
            emitInterface(type, sb, indent, topLevel, mapper, name, params, classVars, interfaces,
                    nested, opaque);
        } else {
            emitClass(type, sb, indent, topLevel, mapper, name, params, classVars, signature,
                    interfaces, nested, opaque);
        }
    }

    private void emitClass(JClass type, StringBuilder sb, String indent, boolean topLevel,
                           TypeMapper mapper, String name, String params, Set<String> classVars,
                           Sig.ClassSig signature, List<String> interfaces, List<JClass> nested,
                           boolean opaque) {
        String extendsClause = "";
        if (!opaque) {
            String superType = mapper.render(signature.superClass(), classVars);
            if (!superType.equals("any") && !superType.equals("Object")) {
                extendsClause = " extends " + superType;
            }
        }
        sb.append(indent).append(topLevel ? "export declare " : "")
          .append(type.isAbstract() ? "abstract " : "")
          .append("class ").append(name).append(params).append(extendsClause).append(" {\n");
        if (!opaque) members(type, sb, indent + "  ", mapper, classVars, MemberStyle.CLASS);
        sb.append(indent).append("}\n");

        // `declare class X implements Y` does not inherit Y's members in a .d.ts, so the
        // interfaces are attached by merging an interface of the same name onto the class.
        if (!interfaces.isEmpty()) {
            sb.append(indent).append(topLevel ? "export " : "").append("interface ").append(name)
              .append(params).append(" extends ").append(String.join(", ", interfaces))
              .append(" {}\n");
        }
        if (!nested.isEmpty()) {
            sb.append(indent).append(topLevel ? "export declare " : "")
              .append("namespace ").append(name).append(" {\n");
            for (JClass child : nested) emitType(child, sb, indent + "  ", false, mapper);
            sb.append(indent).append("}\n");
        }
    }

    private void emitInterface(JClass type, StringBuilder sb, String indent, boolean topLevel,
                               TypeMapper mapper, String name, String params,
                               Set<String> classVars, List<String> interfaces,
                               List<JClass> nested, boolean opaque) {
        String extendsClause = interfaces.isEmpty() ? "" : " extends " + String.join(", ", interfaces);
        sb.append(indent).append(topLevel ? "export " : "").append("interface ").append(name)
          .append(params).append(extendsClause).append(" {\n");
        if (!opaque) members(type, sb, indent + "  ", mapper, classVars, MemberStyle.INTERFACE);
        sb.append(indent).append("}\n");
        emitInterfaceValueSide(type, sb, indent, topLevel, mapper, nested, opaque);
    }

    /**
     * A Java interface is a value at runtime -- {@code Java.type} returns it and its statics and
     * nested types are reachable -- but a TS interface has no value side. Without one the
     * registry entry silently degrades to {@code any}, so emit a namespace (or a stub const when
     * there is nothing to put in it).
     */
    private void emitInterfaceValueSide(JClass type, StringBuilder sb, String indent,
                                        boolean topLevel, TypeMapper mapper, List<JClass> nested,
                                        boolean opaque) {
        String name = type.simpleName();
        boolean hasStatics = !opaque && (hasVisibleStaticField(type) || hasVisibleStaticMethod(type));
        // Only a nested class or enum gives the namespace a value side; a nested interface is
        // a type alone and leaves it uninstantiated.
        boolean hasNestedValues = nested.stream().anyMatch(n -> !n.isInterface());

        if (!hasStatics && !hasNestedValues && nested.isEmpty()) {
            sb.append(indent).append(topLevel ? "export declare " : "").append("const ")
              .append(name).append(": JavaInterface;\n");
            return;
        }
        sb.append(indent).append(topLevel ? "export declare " : "")
          .append("namespace ").append(name).append(" {\n");
        if (hasStatics) members(type, sb, indent + "  ", mapper, Set.of(), MemberStyle.NAMESPACE);
        for (JClass child : nested) emitType(child, sb, indent + "  ", false, mapper);
        if (!hasStatics && !hasNestedValues) {
            // Every member so far is a type; give the namespace a value so `typeof` works.
            sb.append(indent).append("  const __type: JavaInterface;\n");
        }
        sb.append(indent).append("}\n");
    }

    private boolean hasVisibleStaticField(JClass type) {
        return type.fields().stream().anyMatch(m -> isVisible(m) && m.isStatic());
    }

    private boolean hasVisibleStaticMethod(JClass type) {
        return type.methods().stream()
                .anyMatch(m -> isVisible(m) && m.isStatic() && !m.isConstructor());
    }

    private List<String> renderedSupertypes(List<Sig.Type> supertypes, Set<String> classVars,
                                            TypeMapper mapper) {
        List<String> rendered = new ArrayList<>();
        for (Sig.Type supertype : supertypes) {
            String ref = mapper.render(supertype, classVars);
            // An excluded supertype renders as `any`, which cannot appear in an extends clause.
            if (!ref.equals("any")) rendered.add(ref);
        }
        return rendered;
    }

    // ---- members ----

    private void members(JClass type, StringBuilder sb, String indent, TypeMapper mapper,
                         Set<String> classVars, MemberStyle style) {
        List<JMember> fields = visibleFields(type, style);
        Map<String, List<JMember>> methodsByName = groupByName(visibleMethods(type, style));

        Set<String> consumedByFields =
                writeFields(fields, methodsByName, sb, indent, mapper, classVars, style);
        writeMethods(methodsByName, consumedByFields, sb, indent, mapper, classVars, style);
        if (style == MemberStyle.CLASS) writeConstructors(type, sb, indent, mapper, classVars);
    }

    private List<JMember> visibleFields(JClass type, MemberStyle style) {
        List<JMember> fields = new ArrayList<>();
        for (JMember field : type.fields()) {
            if (!isVisible(field)) continue;
            if (style == MemberStyle.NAMESPACE && !field.isStatic()) continue;
            if (style == MemberStyle.INTERFACE && field.isStatic()) continue;
            fields.add(field);
        }
        if (style == MemberStyle.CLASS) fields.addAll(universe.inheritedFields(type));
        return fields;
    }

    private List<JMember> visibleMethods(JClass type, MemberStyle style) {
        List<JMember> methods = new ArrayList<>();
        for (JMember method : type.methods()) {
            if (!isVisible(method) || method.isConstructor()) continue;
            if (style == MemberStyle.NAMESPACE && !method.isStatic()) continue;
            if (style == MemberStyle.INTERFACE && method.isStatic()) continue;
            methods.add(method);
        }
        if (style == MemberStyle.CLASS) methods.addAll(universe.inheritedMethods(type));
        return methods;
    }

    /** Groups overloads, keeping instance and static members with the same name apart. */
    private static Map<String, List<JMember>> groupByName(List<JMember> members) {
        Map<String, List<JMember>> byName = new LinkedHashMap<>();
        for (JMember member : members) {
            byName.computeIfAbsent(slotKey(member), k -> new ArrayList<>()).add(member);
        }
        return byName;
    }

    private static String slotKey(JMember member) {
        return member.name() + "/" + member.isStatic();
    }

    /**
     * Writes the fields, and returns the method groups they absorbed.
     *
     * <p>Java allows a field and a method to share a name; TypeScript does not. Rendering the
     * field's type intersected with the method's call signatures keeps {@code v.x} and
     * {@code v.x()} both working, which is what the host object actually supports.
     */
    private Set<String> writeFields(List<JMember> fields, Map<String, List<JMember>> methodsByName,
                                    StringBuilder sb, String indent, TypeMapper mapper,
                                    Set<String> classVars, MemberStyle style) {
        Set<String> consumed = new LinkedHashSet<>();
        Set<String> written = new LinkedHashSet<>();
        for (JMember field : fields) {
            String key = slotKey(field);
            // A field inherited from a hidden supertype can repeat one already declared here.
            if (!written.add(key)) continue;

            Set<String> vars = field.isStatic() ? Set.of() : classVars;
            String type = mapper.render(Signatures.fieldType(field), vars);
            List<JMember> sameName = methodsByName.get(key);
            if (sameName != null) {
                consumed.add(key);
                type += callSignatures(sameName, mapper, vars);
            }
            sb.append(doc(field.deprecated(), List.of(), indent));
            sb.append(indent).append(fieldKeyword(style, field.isStatic()))
              .append(Names.member(field.name())).append(": ").append(type).append(";\n");
        }
        return consumed;
    }

    private void writeMethods(Map<String, List<JMember>> methodsByName, Set<String> consumed,
                              StringBuilder sb, String indent, TypeMapper mapper,
                              Set<String> classVars, MemberStyle style) {
        for (var group : methodsByName.entrySet()) {
            if (consumed.contains(group.getKey())) continue;
            for (JMember method : group.getValue()) {
                Set<String> vars = method.isStatic() ? Set.of() : classVars;
                sb.append(doc(method.deprecated(), method.exceptions(), indent));
                sb.append(indent).append(methodKeyword(style, method.isStatic()))
                  .append(methodSignature(method, mapper, vars)).append(";\n");
            }
        }
    }

    /** Overloads that erase to the same TS signature collapse into one constructor line. */
    private void writeConstructors(JClass type, StringBuilder sb, String indent, TypeMapper mapper,
                                   Set<String> classVars) {
        Set<String> seen = new LinkedHashSet<>();
        for (JMember method : type.methods()) {
            if (!isVisible(method) || !method.isConstructor()) continue;
            String signature = "constructor(" + parameterList(method, mapper, classVars) + ")";
            if (seen.add(signature)) sb.append(indent).append(signature).append(";\n");
        }
    }

    private static String fieldKeyword(MemberStyle style, boolean isStatic) {
        return switch (style) {
            case NAMESPACE -> "const ";
            case CLASS -> isStatic ? "static " : "";
            case INTERFACE -> "";
        };
    }

    private static String methodKeyword(MemberStyle style, boolean isStatic) {
        return switch (style) {
            case NAMESPACE -> "function ";
            case CLASS -> isStatic ? "static " : "";
            case INTERFACE -> "";
        };
    }

    // ---- signatures ----

    /** {@code name<T>(a: A): R}, as a member declaration. */
    private String methodSignature(JMember method, TypeMapper mapper, Set<String> classVars) {
        Sig.MethodSig signature = Signatures.method(method);
        Set<String> vars = scopeOf(classVars, signature);
        return Names.member(method.name())
                + mapper.renderFormals(signature.formals(), vars, false)
                + "(" + parameterList(method, signature, mapper, vars) + ")"
                + ": " + mapper.render(signature.returnType(), vars);
    }

    /** {@code & { (a: A): R; }}, the call side of a field that shares a name with a method. */
    private String callSignatures(List<JMember> methods, TypeMapper mapper, Set<String> vars) {
        StringBuilder sb = new StringBuilder(" & {");
        for (JMember method : methods) {
            Sig.MethodSig signature = Signatures.method(method);
            Set<String> scope = scopeOf(vars, signature);
            sb.append(" (").append(parameterList(method, signature, mapper, scope)).append("): ")
              .append(mapper.render(signature.returnType(), scope)).append(';');
        }
        return sb.append(" }").toString();
    }

    private String parameterList(JMember method, TypeMapper mapper, Set<String> classVars) {
        Sig.MethodSig signature = Signatures.method(method);
        return parameterList(method, signature, mapper, scopeOf(classVars, signature));
    }

    private String parameterList(JMember method, Sig.MethodSig signature, TypeMapper mapper,
                                 Set<String> vars) {
        List<Sig.Type> parameters = signature.params();
        boolean named = method.hasParameterNames(parameters.size());
        StringBuilder sb = new StringBuilder();
        Set<String> usedNames = new LinkedHashSet<>();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            String name = Names.param(named ? method.parameterNames().get(i) : null, i);
            // Two parameters can carry the same recovered name; the later one goes positional.
            if (!usedNames.add(name)) name = "a" + i;

            boolean last = i == parameters.size() - 1;
            // GraalJS expands a varargs call, so `Path.of("a")` and `Path.of("a", "b")` are both
            // valid; a required JavaArray parameter would reject them.
            if (last && method.isVarargs() && parameters.get(i) instanceof Sig.Arr array) {
                sb.append("...").append(name).append(": ")
                  .append(mapper.render(array.element(), vars)).append("[]");
            } else {
                sb.append(name).append(": ").append(mapper.render(parameters.get(i), vars));
            }
        }
        return sb.toString();
    }

    /** The class's type variables plus the method's own, which shadow them. */
    private static Set<String> scopeOf(Set<String> classVars, Sig.MethodSig signature) {
        Set<String> vars = new LinkedHashSet<>(classVars);
        vars.addAll(Signatures.namesOf(signature.formals()));
        return vars;
    }

    // ---- helpers ----

    /** A JSDoc block for the tags that apply, or "" when there are none or JSDoc is off. */
    private String doc(boolean deprecated, List<String> thrown, String indent) {
        if (!jsDoc) return "";
        List<String> tags = new ArrayList<>();
        if (deprecated) tags.add("@deprecated");
        for (String exception : thrown) tags.add("@throws {" + JvmNames.simpleName(exception) + "}");

        if (tags.isEmpty()) return "";
        if (tags.size() == 1) return indent + "/** " + tags.get(0) + " */\n";
        StringBuilder sb = new StringBuilder(indent + "/**\n");
        for (String tag : tags) sb.append(indent).append(" * ").append(tag).append('\n');
        return sb.append(indent).append(" */\n").toString();
    }

    /** Only public members are emitted, matching what {@code HostAccess.ALL} exposes. */
    private static boolean isVisible(JMember member) {
        return member.isPublic() && !member.isSynthetic() && !member.isBridge();
    }

    /** Module specifier from one top-level class's file to another's. */
    private static String relativePath(String fromTop, String toTop) {
        String[] from = fromTop.split("/");
        String[] to = toTop.split("/");
        int common = 0;
        while (common < from.length - 1 && common < to.length - 1
                && from[common].equals(to[common])) {
            common++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < from.length - 1; i++) sb.append("../");
        if (sb.isEmpty()) sb.append("./");
        for (int i = common; i < to.length; i++) {
            sb.append(to[i]);
            if (i < to.length - 1) sb.append('/');
        }
        return sb.toString();
    }
}
