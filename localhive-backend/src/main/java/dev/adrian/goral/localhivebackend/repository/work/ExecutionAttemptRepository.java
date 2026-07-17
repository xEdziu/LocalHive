package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAttempt;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExecutionAttemptRepository extends JpaRepository<ExecutionAttempt, UUID> {

    Optional<ExecutionAttempt> findByExecution(WorkExecution execution);

    Optional<ExecutionAttempt> findByExecutionAndStatus(WorkExecution execution, ExecutionAttemptStatus status);

    boolean existsByExecution(WorkExecution execution);
}
