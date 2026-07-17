package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExecutionAssignmentRepository extends JpaRepository<ExecutionAssignment, UUID> {

    Optional<ExecutionAssignment> findByExecution(WorkExecution execution);

    boolean existsByExecution(WorkExecution execution);

    boolean existsByWorkerAndExecution_StatusIn(Worker worker, Collection<WorkExecutionStatus> statuses);
}
