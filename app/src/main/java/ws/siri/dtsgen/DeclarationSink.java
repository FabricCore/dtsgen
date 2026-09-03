package ws.siri.dtsgen;

import java.io.IOException;

/**
 * Where generated declarations go. Implement this to send the output somewhere other than a
 * directory -- an archive, an in-memory map, a build system's virtual file system -- and pass
 * it to {@link Declarations#writeTo(DeclarationSink)}.
 *
 * <p>Declarations are handed over one file at a time and are not retained, so a sink that
 * streams keeps peak memory flat no matter how large the output is.
 */
@FunctionalInterface
public interface DeclarationSink {

    /**
     * Accepts one generated file.
     *
     * @param relativePath forward-slash-separated path below the output root, such as
     *                     {@code full/net/minecraft/core/BlockPos.d.ts} or {@code java.d.ts}
     * @param contents     the complete text of the file
     * @throws IOException if the file cannot be stored
     */
    void accept(String relativePath, String contents) throws IOException;
}
