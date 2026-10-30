package com.gambitrate.repository;

import com.gambitrate.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameRepository extends JpaRepository<Game, Long> {

    boolean existsByChesscomId(String chesscomId);

    Optional<Game> findByChesscomId(String chesscomId);

    long countByPlayerId(Long playerId);
}
