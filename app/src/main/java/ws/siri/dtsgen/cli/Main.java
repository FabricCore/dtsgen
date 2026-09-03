package ws.siri.dtsgen.cli;

import ws.siri.dtsgen.Declarations;
import ws.siri.dtsgen.DtsGenerationException;
import ws.siri.dtsgen.DtsGenerator;
import ws.siri.dtsgen.GenerationResult;
import ws.siri.dtsgen.GeneratorConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line front end: it reads a config file, runs a generation, and reports what happened.
 *
 * <p>All of the behaviour lives in the library; this class only maps arguments to a
 * {@link GeneratorConfig} and failures to exit codes, so that driving the generator from a
 * build script is not second best to running it here.
 */
public final class Main {

    private static final String DEFAULT_CONFIG = "dtsgen.jsonc";
    private static final String USAGE = """
            usage: dtsgen [config]

            Generates TypeScript declarations from Java bytecode.
            The config defaults to ./%s; see the README for its keys.
            """.formatted(DEFAULT_CONFIG);

    /** Bad usage, a missing input, or a malformed config. */
    private static final int EXIT_USAGE = 2;
    /** The run started but could not finish. */
    private static final int EXIT_FAILURE = 1;

    private Main() {}

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("-h") || args[0].equals("--help"))) {
            System.out.print(USAGE);
            return;
        }
        if (args.length > 1) {
            System.err.print(USAGE);
            System.exit(EXIT_USAGE);
        }
        Path configPath = Path.of(args.length > 0 ? args[0] : DEFAULT_CONFIG);
        if (!Files.exists(configPath)) {
            System.err.println("no such config: " + configPath.toAbsolutePath());
            System.exit(EXIT_USAGE);
        }
        try {
            run(configPath);
        } catch (DtsGenerationException e) {
            System.err.println(e.getMessage());
            System.exit(EXIT_USAGE);
        } catch (IOException e) {
            System.err.println("failed: " + e);
            System.exit(EXIT_FAILURE);
        }
    }

    private static void run(Path configPath) throws IOException {
        GeneratorConfig config = GeneratorConfig.fromJson(configPath);

        long startedAt = System.nanoTime();
        Declarations declarations = new DtsGenerator(config).generate();
        reportUnreadable(declarations.unreadableClasses());
        System.out.printf("scanned %d classes, emitting %d types in %d files%n",
                declarations.scannedClassCount(), declarations.emittedTypeCount(),
                declarations.moduleCount());

        GenerationResult result = declarations.writeTo(config.outputDirectory());
        System.out.printf("wrote %d files (%.2f MB) + %s (%.2f MB) in %.1fs%n",
                result.moduleFilesWritten(), megabytes(result.moduleBytesWritten()),
                declarations.registryPath(), megabytes(result.registryBytesWritten()),
                (System.nanoTime() - startedAt) / 1e9);
        if (declarations.isRegistryPruned()) {
            System.out.printf("registry pruned to %d classes found in scripts%n",
                    declarations.registryTypeCount());
        }
    }

    /** A class that could not be read silently widens every reference to it, so say so. */
    private static void reportUnreadable(List<String> unreadable) {
        if (unreadable.isEmpty()) return;
        System.err.printf("warning: %d class files could not be read, e.g. %s%n",
                unreadable.size(), unreadable.get(0));
    }

    private static double megabytes(long bytes) {
        return bytes / 1e6;
    }
}
