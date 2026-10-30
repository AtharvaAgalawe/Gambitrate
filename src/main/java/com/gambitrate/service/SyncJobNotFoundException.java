package com.gambitrate.service;

public class SyncJobNotFoundException extends RuntimeException {

    public SyncJobNotFoundException(String username) {
        super("No sync job exists for player '" + username + "'. Start one with POST /api/players/"
                + username + "/sync.");
    }
}
