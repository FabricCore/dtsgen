package ws.siri.dtsgen;

/**
 * A generation run could not proceed for a reason the caller can fix: a malformed config, an
 * unknown mode, or a source path that does not exist.
 *
 * <p>Unchecked on purpose. These are programming or configuration errors rather than the
 * recoverable I/O failures the generator reports as {@link java.io.IOException}, and a library
 * caller that has already validated its own inputs should not have to catch them.
 */
public class DtsGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DtsGenerationException(String message) {
        super(message);
    }

    public DtsGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
