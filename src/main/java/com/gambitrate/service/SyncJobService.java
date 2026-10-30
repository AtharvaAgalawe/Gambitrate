package com.gambitrate.service;

import com.gambitrate.domain.Player;
import com.gambitrate.domain.SyncJob;
import com.gambitrate.domain.SyncStatus;
import com.gambitrate.ingest.ChessComClient;
import com.gambitrate.ingest.PlayerSyncService;
import com.gambitrate.ingest.SyncJobWorker;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.repository.SyncJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Service
public class SyncJobService {

    private static final Logger log = LoggerFactory.getLogger(SyncJobService.class);
    private static final Set<SyncStatus> ACTIVE_STATUSES =
            EnumSet.of(SyncStatus.QUEUED, SyncStatus.RUNNING);

    private final PlayerRepository playerRepository;
    private final SyncJobRepository syncJobRepository;
    private final ChessComClient chessComClient;
    private final SyncJobWorker syncJobWorker;
    private final Clock clock;
    private final Duration staleAfter;

    public SyncJobService(
            PlayerRepository playerRepository,
            SyncJobRepository syncJobRepository,
            ChessComClient chessComClient,
            SyncJobWorker syncJobWorker,
            Clock clock,
            @Value("${gambitrate.sync.stale-after:2h}") Duration staleAfter
    ) {
        this.playerRepository = playerRepository;
        this.syncJobRepository = syncJobRepository;
        this.chessComClient = chessComClient;
        this.syncJobWorker = syncJobWorker;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public SyncJobSnapshot start(String rawUsername) {
        String username = normalizeUsername(rawUsername);
        Player player = findOrCreatePlayer(username);

        var activeJob = syncJobRepository.findFirstByPlayerIdAndStatusInOrderByCreatedAtDesc(
                player.getId(), ACTIVE_STATUSES);
        if (activeJob.isPresent()) {
            SyncJob job = activeJob.get();
            if (!isStale(job)) {
                return snapshot(job, username);
            }
            log.warn("sync_job_stale job_id={} username={} status={}",
                    job.getId(), username, job.getStatus());
            job.markFailed(Instant.now(clock), "Job was abandoned before completion.");
            syncJobRepository.save(job);
        }
        return createAndStartJob(player, username);
    }

    public SyncJobSnapshot status(String rawUsername) {
        String username = normalizeUsername(rawUsername);
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));
        SyncJob job = syncJobRepository.findFirstByPlayerIdOrderByCreatedAtDesc(player.getId())
                .orElseThrow(() -> new SyncJobNotFoundException(username));
        return snapshot(job, username);
    }

    private SyncJobSnapshot createAndStartJob(Player player, String username) {
        SyncJob job;
        try {
            job = syncJobRepository.save(new SyncJob(player));
        } catch (DataIntegrityViolationException exception) {
            job = syncJobRepository.findFirstByPlayerIdAndStatusInOrderByCreatedAtDesc(
                            player.getId(), ACTIVE_STATUSES)
                    .orElseThrow(() -> exception);
        }
        SyncJobSnapshot snapshot = snapshot(job, username);
        syncJobWorker.runAsync(job.getId());
        return snapshot;
    }

    private Player findOrCreatePlayer(String username) {
        return playerRepository.findByUsername(username).orElseGet(() -> {
            chessComClient.assertPlayerExists(username);
            try {
                return playerRepository.save(new Player(username));
            } catch (DataIntegrityViolationException exception) {
                return playerRepository.findByUsername(username).orElseThrow(() -> exception);
            }
        });
    }

    private static SyncJobSnapshot snapshot(SyncJob job, String username) {
        return new SyncJobSnapshot(
                job.getId(),
                username,
                job.getStatus().name(),
                job.getMonthsTotal(),
                job.getMonthsDone(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getError()
        );
    }

    private boolean isStale(SyncJob job) {
        Instant reference = job.getStartedAt() == null ? job.getCreatedAt() : job.getStartedAt();
        return reference.isBefore(Instant.now(clock).minus(staleAfter));
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
