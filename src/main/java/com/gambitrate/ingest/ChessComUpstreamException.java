package com.gambitrate.ingest;

public class ChessComUpstreamException extends RuntimeException {

    public ChessComUpstreamException(String message) {
        super(message);
    }

    public ChessComUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
