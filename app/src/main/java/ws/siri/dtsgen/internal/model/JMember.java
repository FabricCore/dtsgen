package ws.siri.dtsgen.internal.model;

import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * One field or method, exactly as the class file declares it.
 *
 * @param access         JVM access flags
 * @param name           member name, {@code <init>} for a constructor
 * @param descriptor     erased descriptor, always present
 * @param signature      generic signature from the Signature attribute, or null when absent;
 *                       preferred over {@code descriptor} because it carries type arguments
 * @param deprecated     true when the class file marks the member deprecated
 * @param parameterNames real parameter names, empty when none could be recovered
 * @param exceptions     internal names of the declared checked exceptions
 */
public record JMember(int access, String name, String descriptor, String signature,
                      boolean deprecated, List<String> parameterNames, List<String> exceptions) {

    public JMember {
        parameterNames = List.copyOf(parameterNames);
        exceptions = List.copyOf(exceptions);
    }

    public boolean isStatic() { return (access & Opcodes.ACC_STATIC) != 0; }

    public boolean isPublic() { return (access & Opcodes.ACC_PUBLIC) != 0; }

    public boolean isSynthetic() { return (access & Opcodes.ACC_SYNTHETIC) != 0; }

    public boolean isBridge() { return (access & Opcodes.ACC_BRIDGE) != 0; }

    public boolean isAbstract() { return (access & Opcodes.ACC_ABSTRACT) != 0; }

    public boolean isConstructor() { return name.equals("<init>"); }

    /** The trailing parameter is a varargs array, which GraalJS expands at the call site. */
    public boolean isVarargs() { return (access & Opcodes.ACC_VARARGS) != 0; }

    /** True when {@link #parameterNames()} covers every parameter of this member. */
    public boolean hasParameterNames(int parameterCount) {
        return parameterNames.size() == parameterCount;
    }

    /** The generic signature when the class file carries one, else the erased descriptor. */
    public String signatureOrDescriptor() {
        return signature != null ? signature : descriptor;
    }

    /** Identifies a member within its declaring class, for override and clash detection. */
    public String erasedKey() {
        return name + descriptor;
    }
}
