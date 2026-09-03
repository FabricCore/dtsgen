package ws.siri.dtsgen.internal.model;

import org.objectweb.asm.Opcodes;

import ws.siri.dtsgen.internal.JvmNames;

import java.util.ArrayList;
import java.util.List;

/**
 * One class, interface, enum or annotation, keyed by its JVM internal name
 * ({@code net/minecraft/core/BlockPos$MutableBlockPos}).
 *
 * <p>Immutable once built; the scanner assembles one through {@link Builder} as ASM visits the
 * class file.
 */
public final class JClass {

    private final String internalName;
    private final int access;
    private final String signature;
    private final String superName;
    private final List<String> interfaces;
    private final List<JMember> fields;
    private final List<JMember> methods;
    private final boolean deprecated;
    private final Integer nestedAccess;

    private JClass(Builder b) {
        this.internalName = b.internalName;
        this.access = b.access;
        this.signature = b.signature;
        this.superName = b.superName;
        this.interfaces = List.copyOf(b.interfaces);
        this.fields = List.copyOf(b.fields);
        this.methods = List.copyOf(b.methods);
        this.deprecated = b.deprecated;
        this.nestedAccess = b.nestedAccess;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String internalName() { return internalName; }

    /** Generic class signature from the Signature attribute, or null when absent. */
    public String signature() { return signature; }

    /** Internal name of the superclass; null only for {@code java/lang/Object} itself. */
    public String superName() { return superName; }

    public List<String> interfaces() { return interfaces; }

    public List<JMember> fields() { return fields; }

    public List<JMember> methods() { return methods; }

    public boolean isDeprecated() { return deprecated; }

    /**
     * Whether a script can reach this type at all.
     *
     * <p>Read from the InnerClasses attribute when there is one, because it is authoritative
     * for a nested type: a nested class's top-level {@code access_flags} lose the distinction
     * between public and package-private.
     */
    public boolean isPublic() { return (effectiveAccess() & Opcodes.ACC_PUBLIC) != 0; }

    private int effectiveAccess() { return nestedAccess != null ? nestedAccess : access; }

    public boolean isInterface() { return (access & Opcodes.ACC_INTERFACE) != 0; }

    public boolean isAbstract() { return (access & Opcodes.ACC_ABSTRACT) != 0; }

    /** The top-level class whose file this type is emitted into. */
    public String topLevelName() { return JvmNames.topLevel(internalName); }

    /** Dotted package of the enclosing top-level class, empty for the default package. */
    public String packageName() { return JvmNames.packageName(internalName); }

    /** The innermost name segment, as the emitted declaration spells it. */
    public String simpleName() { return JvmNames.simpleName(internalName); }

    @Override
    public String toString() { return internalName; }

    /** Collects the parts of a class file in ASM's visiting order. */
    public static final class Builder {

        private String internalName;
        private int access;
        private String signature;
        private String superName;
        private List<String> interfaces = List.of();
        private final List<JMember> fields = new ArrayList<>();
        private final List<JMember> methods = new ArrayList<>();
        private boolean deprecated;
        private Integer nestedAccess;

        private Builder() {}

        public Builder header(String internalName, int access, String signature, String superName,
                             List<String> interfaces) {
            this.internalName = internalName;
            this.access = access;
            this.signature = signature;
            this.superName = superName;
            this.interfaces = interfaces;
            this.deprecated = (access & Opcodes.ACC_DEPRECATED) != 0;
            return this;
        }

        /** Records the access flags from the InnerClasses entry for this class itself. */
        public Builder nestedAccess(int access) {
            this.nestedAccess = access;
            return this;
        }

        public Builder addField(JMember field) {
            fields.add(field);
            return this;
        }

        public Builder addMethod(JMember method) {
            methods.add(method);
            return this;
        }

        /** Null until the class header has been visited; used to name a class that failed to read. */
        public String internalName() { return internalName; }

        public JClass build() {
            if (internalName == null) throw new IllegalStateException("no class header visited");
            return new JClass(this);
        }
    }
}
