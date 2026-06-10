package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    Optional<Worker> findByHostname(String hostname);

    List<Worker> findAllByStatus(WorkerStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Worker w
        set w.status = :offlineStatus
        where w.status = :activeStatus
          and (w.lastHeartbeatAt is null or w.lastHeartbeatAt < :cutoffTime)
        """)
    int markInactiveWorkersOffline(WorkerStatus activeStatus,
                                   WorkerStatus offlineStatus,
                                   LocalDateTime cutoffTime);

    @Query("""
        select w from Worker w
        where w.status = :status
          and w.sharedRamMb >= :requiredRamMb
        order by w.sharedRamMb desc
        """)
    List<Worker> findEligibleWorkersForTaskAssignment(
        WorkerStatus status,
        int requiredRamMb
    );
}