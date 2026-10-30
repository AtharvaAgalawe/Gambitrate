package com.gambitrate.repository;

import com.gambitrate.domain.SyncJob;
import com.gambitrate.domain.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    Optional<SyncJob> findFirstByPlayerIdAndStatusInOrderByCreatedAtDesc(
            Long playerId,
            Collection<SyncStatus> statuses
    );

    Optional<SyncJob> findFirstByPlayerIdOrderByCreatedAtDesc(Long playerId);

    @Query("select p.username from SyncJob j join j.player p where j.id = :jobId")
    Optional<String> findPlayerUsernameByJobId(@Param("jobId") Long jobId);
}
