package com.gambitrate.ingest;

import com.gambitrate.domain.Game;
import com.gambitrate.domain.Player;
import com.gambitrate.ingest.dto.MonthlyGame;
import com.gambitrate.ingest.dto.MonthlyGamesResponse;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.service.PlayerNotSyncedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class PlayerSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSyncService.class);

    private final ChessComClient chessComClient;
    private final PgnParser pgnParser;
    private final GameFactory gameFactory;
    private final MonthPersistenceService monthPersistenceService;
    private final PlayerRepository playerRepository;
    private final Clock clock;

    public PlayerSyncService(
            ChessComClient chessComClient,
            PgnParser pgnParser,
            GameFactory gameFactory,
            MonthPersistenceService monthPersistenceService,
            PlayerRepository playerRepository,
            Clock clock
    ) {
        this.chessComClient = chessComClient;
        this.pgnParser = pgnParser;
        this.gameFactory = gameFactory;
        this.monthPersistenceService = monthPersistenceService;
        this.playerRepository = playerRepository;
        this.clock = clock;
    }

    public SyncSummary sync(String rawUsername, SyncProgressListener progress) {
        String username = normalizeUsername(rawUsername);
        long started = System.nanoTime();
        log.info("sync_started username={}", username);

        List<String> archiveUrls = chessComClient.getArchives(username);
        List<ArchiveMonth> allArchives = archiveUrls.stream()
                .map(PlayerSyncService::toArchiveMonth)
                .sorted(Comparator.comparing(ArchiveMonth::month))
                .toList();

        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));
        YearMonth currentMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        List<ArchiveMonth> archives = allArchives.stream()
                .filter(archive -> shouldFetch(archive.month(), player.getLastSyncedMonth(), currentMonth))
                .toList();

        progress.monthsDiscovered(archives.size());
        log.info("sync_months_selected username={} available={} selected={} current_month={}",
                username, allArchives.size(), archives.size(), currentMonth);

        int monthsProcessed = 0;
        int gamesSeen = 0;
        int variantsSkipped = 0;
        int gamesImported = 0;
        int gamesSkippedExisting = 0;
        long movesImported = 0;

        for (ArchiveMonth archive : archives) {
            MonthlyGamesResponse month = chessComClient.getGamesForMonth(
                    archive.url(),
                    archive.month(),
                    currentMonth
            );
            List<MonthlyGame> sourceGames = month.games() == null ? List.of() : month.games();
            List<Game> parsedGames = new ArrayList<>();

            for (MonthlyGame source : sourceGames) {
                if (!"chess".equalsIgnoreCase(source.rules())) {
                    variantsSkipped++;
                    continue;
                }
                gamesSeen++;
                ParsedPgn parsedPgn = pgnParser.parse(source.pgn());
                parsedGames.add(gameFactory.create(player, source, parsedPgn));
            }

            MonthPersistenceService.MonthSaveResult result =
                    monthPersistenceService.persistMonth(player, parsedGames);
            monthsProcessed++;
            gamesImported += result.imported();
            gamesSkippedExisting += result.skipped();
            movesImported += result.movesImported();

            Instant newestGame = parsedGames.stream()
                    .map(Game::getPlayedAt)
                    .max(Instant::compareTo)
                    .orElse(null);
            player.markSynced(Instant.now(clock), archive.month().toString(), newestGame);
            playerRepository.save(player);
            progress.monthCompleted(monthsProcessed);
        }

        if (archives.isEmpty()) {
            player.markSynced(Instant.now(clock), player.getLastSyncedMonth(), player.getLastGameEndTime());
            playerRepository.save(player);
        }

        log.info("sync_finished username={} months={} games_seen={} games_imported={} "
                        + "games_skipped_existing={} variants_skipped={} moves_imported={} latency_ms={}",
                username, monthsProcessed, gamesSeen, gamesImported, gamesSkippedExisting,
                variantsSkipped, movesImported, (System.nanoTime() - started) / 1_000_000);

        return new SyncSummary(
                username,
                monthsProcessed,
                gamesSeen,
                variantsSkipped,
                gamesImported,
                gamesSkippedExisting,
                movesImported
        );
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean shouldFetch(YearMonth month, String lastSyncedMonth, YearMonth currentMonth) {
        if (month.equals(currentMonth)) {
            return true;
        }
        return lastSyncedMonth == null || !month.isBefore(YearMonth.parse(lastSyncedMonth));
    }

    private static ArchiveMonth toArchiveMonth(String url) {
        try {
            String path = URI.create(url).getPath();
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Archive URL has no year/month: " + url);
            }
            YearMonth month = YearMonth.of(
                    Integer.parseInt(parts[parts.length - 2]),
                    Integer.parseInt(parts[parts.length - 1])
            );
            return new ArchiveMonth(url, month);
        } catch (RuntimeException exception) {
            throw new ChessComUpstreamException("Chess.com returned an invalid archive URL: " + url, exception);
        }
    }

    private record ArchiveMonth(String url, YearMonth month) {
    }
}
