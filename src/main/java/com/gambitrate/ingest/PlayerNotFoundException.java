package com.gambitrate.ingest;

public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(String username) {
        super("Chess.com player '" + username + "' was not found.");
    }
}
