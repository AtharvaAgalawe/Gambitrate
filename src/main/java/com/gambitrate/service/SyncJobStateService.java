package com.gambitrate.service;

import com.gambitrate.domain.SyncJob;
import com.gambitrate.domain.SyncStatus;
import com.gambitrate.repository.SyncJobRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class SyncJobStateService {

    private final SyncJobRepository syncJobRepository;
    private final Clock clock;

    public SyncJobStateService(SyncJobRepository syncJobRepository, Clock clock) {
        this.syncJobRepository = syncJobRepository;
        this.clock = clock;
    }

    public boolean markRunning(Long jobId) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != SyncStatus.QUEUED) {
            return false;
        }
        job.markRunning(Instant.now(clock));
        syncJobRepository.save(job);
        return true;
    }

    public void monthsDiscovered(Long jobId, int total) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() == SyncStatus.DONE || job.getStatus() == SyncStatus.FAILED) {
            return;
        }
        job.setMonthsTotal(total);
        syncJobRepository.save(job);
    }

    public void monthCompleted(Long jobId, int completed) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() == SyncStatus.DONE || job.getStatus() == SyncStatus.FAILED) {
            return;
        }
        job.recordMonthDone(completed);
        syncJobRepository.save(job);
    }

    public void markDone(Long jobId) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.markDone(Instant.now(clock));
        syncJobRepository.save(job);
    }

    public void markFailed(Long jobId, Throwable error) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        job.markFailed(Instant.now(clock), message);
        syncJobRepository.save(job);
    }
}
