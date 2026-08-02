package info.fkhodkov.rankedchoice.core.ballot;

/** Thrown when ballot input doesn't conform to the expected file format. */
public class BallotParseException extends RuntimeException {

    public BallotParseException(String message) {
        super(message);
    }

    public BallotParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
