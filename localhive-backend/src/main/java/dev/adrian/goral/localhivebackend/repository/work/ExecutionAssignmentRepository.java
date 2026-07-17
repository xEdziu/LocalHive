package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExecutionAssignmentRepository extends JpaRepository<ExecutionAssignment, UUID> {

    Optional<ExecutionAssignment> findByExecution(WorkExecution execution);

    @EntityGraph(attributePaths = {"execution", "execution.definitionVersion", "worker"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ExecutionAssignment> findFirstByWorker_IdAndExecution_StatusOrderByAssignedAtAsc(
            UUID workerId,
            WorkExecutionStatus status
    );

    @EntityGraph(attributePaths = {"execution", "worker"})
    Optional<ExecutionAssignment> findByExecution_IdAndWorker_Id(UUID executionId, UUID workerId);

    @EntityGraph(attributePaths = {"execution"})
    List<ExecutionAssignment> findByExecution_StatusAndLeaseExpiresAtLessThanEqual(
            WorkExecutionStatus status,
            LocalDateTime leaseExpiresAt
    );

    boolean existsByExecution(WorkExecution execution);

    boolean existsByWorkerAndExecution_StatusIn(Worker worker, Collection<WorkExecutionStatus> statuses);
}
