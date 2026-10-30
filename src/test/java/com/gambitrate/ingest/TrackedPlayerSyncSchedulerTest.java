package com.gambitrate.ingest;

import com.gambitrate.domain.Player;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackedPlayerSyncSchedulerTest {

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private SyncJobService syncJobService;

    @InjectMocks
    private TrackedPlayerSyncScheduler scheduler;

    @Test
    void resyncTrackedPlayers_startsAJobForEveryTrackedPlayer() {
        when(playerRepository.findByLastSyncedAtIsNotNull())
                .thenReturn(List.of(new Player("one"), new Player("two")));

        scheduler.resyncTrackedPlayers();

        verify(syncJobService).start("one");
        verify(syncJobService).start("two");
    }

    @Test
    void resyncTrackedPlayers_continuesAfterOnePlayerFails() {
        when(playerRepository.findByLastSyncedAtIsNotNull())
                .thenReturn(List.of(new Player("one"), new Player("two")));
        doThrow(new RuntimeException("boom")).when(syncJobService).start("one");

        scheduler.resyncTrackedPlayers();

        verify(syncJobService).start("one");
        verify(syncJobService).start("two");
    }
}
