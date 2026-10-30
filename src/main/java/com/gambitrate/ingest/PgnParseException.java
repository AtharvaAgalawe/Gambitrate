package com.gambitrate.ingest;

public class PgnParseException extends RuntimeException {

    public PgnParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public PgnParseException(String message) {
        super(message);
    }
}
