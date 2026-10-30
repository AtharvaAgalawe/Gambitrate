package com.gambitrate.repository;

import com.gambitrate.domain.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByUsername(String username);

    List<Player> findByLastSyncedAtIsNotNull();
}
