package com.gambitrate.ingest;

import com.gambitrate.domain.Game;
import com.gambitrate.domain.Player;
import com.gambitrate.repository.GameRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MonthPersistenceService {

    private final GameRepository gameRepository;

    public MonthPersistenceService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    @Transactional
    public MonthSaveResult persistMonth(Player player, List<Game> games) {
        int imported = 0;
        int skipped = 0;
        long movesImported = 0;
        Set<String> seenInBatch = new HashSet<>();

        for (Game game : games) {
            if (!seenInBatch.add(game.getChesscomId()) || gameRepository.existsByChesscomId(game.getChesscomId())) {
                skipped++;
                continue;
            }
            gameRepository.save(game);
            imported++;
            movesImported += game.getMoves().size();
        }

        return new MonthSaveResult(imported, skipped, movesImported);
    }

    public record MonthSaveResult(int imported, int skipped, long movesImported) {
    }
}
