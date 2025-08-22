package dev.shaaf.jgraphlet;

/**
 * Unchecked exception indicating a failure while running a task within the pipeline.
 */
public class TaskRunException extends RuntimeException {

    /**
     * Creates a new exception with the specified detail message.
     *
     * @param s the detail message
     */
    public TaskRunException(String s) {
        super(s);
    }

    /**
     * Creates a new exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public TaskRunException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with the specified cause.
     *
     * @param cause the cause
     */
    public TaskRunException(Throwable cause) {
        super(cause);
    }

}
