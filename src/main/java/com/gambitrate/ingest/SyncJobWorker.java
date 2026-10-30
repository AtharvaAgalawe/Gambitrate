package com.gambitrate.ingest;

import com.gambitrate.repository.SyncJobRepository;
import com.gambitrate.service.SyncJobStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncJobWorker.class);

    private final SyncJobRepository syncJobRepository;
    private final SyncJobStateService syncJobStateService;
    private final PlayerSyncService playerSyncService;

    public SyncJobWorker(
            SyncJobRepository syncJobRepository,
            SyncJobStateService syncJobStateService,
            PlayerSyncService playerSyncService
    ) {
        this.syncJobRepository = syncJobRepository;
        this.syncJobStateService = syncJobStateService;
        this.playerSyncService = playerSyncService;
    }

    @Async("syncTaskExecutor")
    public void runAsync(Long jobId) {
        Optional<String> username = syncJobRepository.findPlayerUsernameByJobId(jobId);
        if (username.isEmpty()) {
            log.warn("sync_job_missing job_id={}", jobId);
            return;
        }
        if (!syncJobStateService.markRunning(jobId)) {
            log.warn("sync_job_not_startable job_id={}", jobId);
            return;
        }

        log.info("sync_job_started job_id={} username={}", jobId, username.get());
        try {
            SyncSummary summary = playerSyncService.sync(username.get(), new SyncProgressListener() {
                @Override
                public void monthsDiscovered(int total) {
                    syncJobStateService.monthsDiscovered(jobId, total);
                }

                @Override
                public void monthCompleted(int completed) {
                    syncJobStateService.monthCompleted(jobId, completed);
                }
            });
            syncJobStateService.markDone(jobId);
            log.info("sync_job_finished job_id={} username={} months={} imported={} skipped={}",
                    jobId, username.get(), summary.monthsProcessed(),
                    summary.gamesImported(), summary.gamesSkippedExisting());
        } catch (RuntimeException exception) {
            syncJobStateService.markFailed(jobId, exception);
            log.error("sync_job_failed job_id={} username={} message={}",
                    jobId, username.get(), exception.getMessage(), exception);
        }
    }
}
