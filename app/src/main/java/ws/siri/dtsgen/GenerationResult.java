package ws.siri.dtsgen;

import java.nio.file.Path;

/**
 * What a call to {@link Declarations#writeTo(Path)} put on disk.
 *
 * @param outputDirectory      the directory written to
 * @param moduleFilesWritten   number of per-class module files under {@code full/}
 * @param moduleBytesWritten   total size of those files
 * @param registryBytesWritten size of the registry file
 */
public record GenerationResult(Path outputDirectory, int moduleFilesWritten,
                               long moduleBytesWritten, long registryBytesWritten) {

    /** Modules plus registry. */
    public long totalBytesWritten() {
        return moduleBytesWritten + registryBytesWritten;
    }

    /** Modules plus the single registry file. */
    public int totalFilesWritten() {
        return moduleFilesWritten + 1;
    }
}
