package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkExecutionAssignmentService {

    private static final Set<WorkExecutionStatus> ACTIVE_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.ASSIGNED,
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );

    private final WorkExecutionRepository executionRepository;
    private final WorkerRepository workerRepository;
    private final ExecutionAssignmentRepository assignmentRepository;

    @Transactional
    public ExecutionAssignment assignExecution(UUID executionId,
                                               UUID workerId,
                                               ExecutionAssignmentMode assignmentMode,
                                               LocalDateTime assignedAt) {
        return assignExecution(
                executionId,
                workerId,
                assignmentMode,
                assignedAt,
                WorkerEligibility.APPROVED_ONLINE_AVAILABLE,
                true
        );
    }

    @Transactional
    public ExecutionAssignment assignExecutionToApprovedWorker(UUID executionId,
                                                              UUID workerId,
                                                              ExecutionAssignmentMode assignmentMode,
                                                              LocalDateTime assignedAt) {
        return assignExecution(
                executionId,
                workerId,
                assignmentMode,
                assignedAt,
                WorkerEligibility.APPROVED_ONLY,
                false
        );
    }

    private ExecutionAssignment assignExecution(UUID executionId,
                                                UUID workerId,
                                                ExecutionAssignmentMode assignmentMode,
                                                LocalDateTime assignedAt,
                                                WorkerEligibility workerEligibility,
                                                boolean requireNoActiveExecution) {
        ExecutionAssignmentMode validAssignmentMode = Objects.requireNonNull(
                assignmentMode,
                "assignmentMode must not be null."
        );
        LocalDateTime validAssignedAt = Objects.requireNonNull(assignedAt, "assignedAt must not be null.");
        WorkExecution execution = findExecution(executionId);
        Worker worker = findWorker(workerId);

        requireQueuedExecution(execution);
        requireNoExistingAssignment(execution);
        workerEligibility.require(worker);
        if (requireNoActiveExecution) {
            requireWorkerHasNoActiveExecution(worker);
        }

        execution.markAssigned(validAssignedAt);
        ExecutionAssignment assignment = ExecutionAssignment.create(
                execution,
                worker,
                validAssignmentMode,
                validAssignedAt
        );
        return assignmentRepository.save(assignment);
    }

    private WorkExecution findExecution(UUID executionId) {
        if (executionId == null) {
            throw new IllegalArgumentException("executionId must not be null.");
        }

        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Work execution not found: " + executionId));
    }

    private Worker findWorker(UUID workerId) {
        if (workerId == null) {
            throw new IllegalArgumentException("workerId must not be null.");
        }

        return workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
    }

    private static void requireQueuedExecution(WorkExecution execution) {
        if (execution.getStatus() != WorkExecutionStatus.QUEUED) {
            throw new IllegalStateException(
                    "Execution must be QUEUED to be assigned. Current status: " + execution.getStatus()
            );
        }
    }

    private void requireNoExistingAssignment(WorkExecution execution) {
        if (assignmentRepository.existsByExecution(execution)) {
            throw new IllegalStateException("Execution is already assigned.");
        }
    }

    private static void requireEligibleWorker(Worker worker) {
        if (worker.getApprovalStatus() != WorkerApprovalStatus.APPROVED
                || worker.getConnectionStatus() != WorkerConnectionStatus.ONLINE
                || worker.getAvailabilityStatus() != WorkerAvailabilityStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Worker must be APPROVED, ONLINE, and AVAILABLE to receive execution assignment."
            );
        }
    }

    private static void requireApprovedWorker(Worker worker) {
        if (worker.getApprovalStatus() != WorkerApprovalStatus.APPROVED) {
            throw new IllegalStateException(
                    "Worker must be APPROVED to receive execution assignment."
            );
        }
    }

    private void requireWorkerHasNoActiveExecution(Worker worker) {
        if (assignmentRepository.existsByWorkerAndExecution_StatusIn(worker, ACTIVE_EXECUTION_STATUSES)) {
            throw new IllegalStateException("Worker already has an active execution assignment.");
        }
    }

    private enum WorkerEligibility {
        APPROVED_ONLINE_AVAILABLE {
            @Override
            void require(Worker worker) {
                requireEligibleWorker(worker);
            }
        },
        APPROVED_ONLY {
            @Override
            void require(Worker worker) {
                requireApprovedWorker(worker);
            }
        };

        abstract void require(Worker worker);
    }
}
