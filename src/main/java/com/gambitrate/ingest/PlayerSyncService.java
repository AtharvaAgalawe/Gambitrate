package com.gambitrate.ingest;

import com.gambitrate.domain.Game;
import com.gambitrate.domain.Player;
import com.gambitrate.ingest.dto.MonthlyGame;
import com.gambitrate.ingest.dto.MonthlyGamesResponse;
import com.gambitrate.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
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

    public PlayerSyncService(
            ChessComClient chessComClient,
            PgnParser pgnParser,
            GameFactory gameFactory,
            MonthPersistenceService monthPersistenceService,
            PlayerRepository playerRepository
    ) {
        this.chessComClient = chessComClient;
        this.pgnParser = pgnParser;
        this.gameFactory = gameFactory;
        this.monthPersistenceService = monthPersistenceService;
        this.playerRepository = playerRepository;
    }

    public SyncSummary sync(String rawUsername) {
        String username = normalizeUsername(rawUsername);
        long started = System.nanoTime();
        log.info("sync_started username={}", username);

        List<String> archiveUrls = chessComClient.getArchives(username);
        List<ArchiveMonth> archives = archiveUrls.stream()
                .map(PlayerSyncService::toArchiveMonth)
                .sorted(Comparator.comparing(ArchiveMonth::month))
                .toList();

        Player player = findOrCreatePlayer(username);

        int monthsProcessed = 0;
        int gamesSeen = 0;
        int variantsSkipped = 0;
        int gamesImported = 0;
        int gamesSkippedExisting = 0;
        long movesImported = 0;

        for (ArchiveMonth archive : archives) {
            MonthlyGamesResponse month = chessComClient.getGamesForMonth(archive.url());
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
        }

        String lastSyncedMonth = archives.isEmpty() ? null : archives.get(archives.size() - 1).month();
        player.markSynced(Instant.now(), lastSyncedMonth);
        playerRepository.save(player);

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

    private Player findOrCreatePlayer(String username) {
        return playerRepository.findByUsername(username)
                .orElseGet(() -> playerRepository.save(new Player(username)));
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static ArchiveMonth toArchiveMonth(String url) {
        try {
            String path = URI.create(url).getPath();
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Archive URL has no year/month: " + url);
            }
            String month = parts[parts.length - 2] + "-" + parts[parts.length - 1];
            if (!month.matches("\\d{4}-\\d{2}")) {
                throw new IllegalArgumentException("Archive URL has an invalid year/month: " + url);
            }
            return new ArchiveMonth(url, month);
        } catch (RuntimeException exception) {
            throw new ChessComUpstreamException("Chess.com returned an invalid archive URL: " + url, exception);
        }
    }

    private record ArchiveMonth(String url, String month) {
    }
}
