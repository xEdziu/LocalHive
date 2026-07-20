package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    @Query("""
        select w
        from Worker w
        where w.approvalStatus = :approvalStatus
          and w.connectionStatus = :connectionStatus
          and w.availabilityStatus = :availabilityStatus
          and not exists (
              select assignment.id
              from ExecutionAssignment assignment
              where assignment.worker = w
                and assignment.execution.status in :activeStatuses
          )
        """)
    List<Worker> findWorkerSelectionCandidates(
            @Param("approvalStatus") WorkerApprovalStatus approvalStatus,
            @Param("connectionStatus") WorkerConnectionStatus connectionStatus,
            @Param("availabilityStatus") WorkerAvailabilityStatus availabilityStatus,
            @Param("activeStatuses") Collection<WorkExecutionStatus> activeStatuses
    );
}
