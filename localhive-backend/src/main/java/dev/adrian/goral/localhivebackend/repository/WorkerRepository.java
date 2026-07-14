package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    Optional<Worker> findByHostname(String hostname);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Worker w
        set w.connectionStatus = :offlineStatus
        where w.approvalStatus = :approvalStatus
          and w.connectionStatus = :onlineStatus
          and (w.lastHeartbeatAt is null or w.lastHeartbeatAt < :cutoffTime)
        """)
    int markInactiveWorkersOffline(WorkerApprovalStatus approvalStatus,
                                   WorkerConnectionStatus onlineStatus,
                                   WorkerConnectionStatus offlineStatus,
                                   LocalDateTime cutoffTime);
}
