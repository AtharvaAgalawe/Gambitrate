package com.gambitrate.ingest;

import com.gambitrate.domain.Player;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.service.SyncJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrackedPlayerSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrackedPlayerSyncScheduler.class);

    private final PlayerRepository playerRepository;
    private final SyncJobService syncJobService;

    public TrackedPlayerSyncScheduler(
            PlayerRepository playerRepository,
            SyncJobService syncJobService
    ) {
        this.playerRepository = playerRepository;
        this.syncJobService = syncJobService;
    }

    @Scheduled(
            fixedDelayString = "${gambitrate.sync.scheduler.fixed-delay:PT1H}",
            initialDelayString = "${gambitrate.sync.scheduler.initial-delay:PT10M}"
    )
    public void resyncTrackedPlayers() {
        for (Player player : playerRepository.findByLastSyncedAtIsNotNull()) {
            try {
                syncJobService.start(player.getUsername());
            } catch (RuntimeException exception) {
                log.error("scheduled_sync_failed username={} message={}",
                        player.getUsername(), exception.getMessage(), exception);
            }
        }
    }
}
