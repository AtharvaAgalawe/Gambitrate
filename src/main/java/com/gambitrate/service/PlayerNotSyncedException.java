package com.gambitrate.service;

public class PlayerNotSyncedException extends RuntimeException {

    public PlayerNotSyncedException(String username) {
        super("Player '" + username + "' has not been synced yet. "
                + "Call POST /api/players/" + username + "/sync first.");
    }
}
