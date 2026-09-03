/**
 * Generates TypeScript declarations from Java bytecode.
 *
 * <p>Only {@link ws.siri.dtsgen} is exported: it holds the whole supported surface -- the
 * generator, its configuration, and the declarations it produces. Everything under
 * {@code ws.siri.dtsgen.internal} is an implementation detail and may change at any time, and
 * {@code ws.siri.dtsgen.cli} is the command-line front end rather than an API.
 */
module ws.siri.dtsgen {

    requires org.objectweb.asm;
    requires com.google.gson;

    exports ws.siri.dtsgen;

    // Gson populates the JSON binding reflectively; nothing else needs to see that package.
    opens ws.siri.dtsgen.internal.json to com.google.gson;
}
