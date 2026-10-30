package com.gambitrate.ingest;

public interface SyncProgressListener {

    void monthsDiscovered(int total);

    void monthCompleted(int completed);
}
