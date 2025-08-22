package dev.jgraphlet;

public class TaskRunException extends RuntimeException {

    public TaskRunException(String s) {
        super(s);
    }

    public TaskRunException(String message, Throwable cause) {
        super(message, cause);
    }

    public TaskRunException(Throwable cause) {
        super(cause);
    }

}
