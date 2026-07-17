package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkExecutionClaimService {

    public static final int LEASE_DURATION_SECONDS = 60;

    private final WorkerRepository workerRepository;
    private final ExecutionAssignmentRepository assignmentRepository;
    private final WorkExecutionLifecycleService lifecycleService;
    private final ExecutionLeaseTokenService leaseTokenService;

    @Transactional
    public Optional<ClaimedExecution> claimNextAssignedExecution(UUID workerId, LocalDateTime now) {
        LocalDateTime claimedAt = Objects.requireNonNull(now, "now must not be null.");
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        requireEligibleWorker(worker);

        Optional<ExecutionAssignment> assignmentCandidate =
                assignmentRepository.findFirstByWorker_IdAndExecution_StatusOrderByAssignedAtAsc(
                        workerId,
                        WorkExecutionStatus.ASSIGNED
                );
        if (assignmentCandidate.isEmpty()) {
            return Optional.empty();
        }

        ExecutionAssignment assignment = assignmentCandidate.get();
        WorkExecution execution = lifecycleService.markClaimed(assignment.getExecution().getId(), claimedAt);
        String rawLeaseToken = leaseTokenService.generateToken();
        assignment.claim(
                leaseTokenService.hashToken(rawLeaseToken),
                claimedAt,
                claimedAt.plusSeconds(LEASE_DURATION_SECONDS)
        );

        return Optional.of(new ClaimedExecution(execution, assignment, rawLeaseToken));
    }

    @Transactional
    public int expireStaleClaims(LocalDateTime now) {
        LocalDateTime expiryTime = Objects.requireNonNull(now, "now must not be null.");
        List<ExecutionAssignment> staleAssignments =
                assignmentRepository.findByExecution_StatusAndLeaseExpiresAtLessThanEqual(
                        WorkExecutionStatus.CLAIMED,
                        expiryTime
                );
        staleAssignments.forEach(assignment -> lifecycleService.expire(assignment.getExecution().getId(), expiryTime));
        return staleAssignments.size();
    }

    private static void requireEligibleWorker(Worker worker) {
        if (worker.getApprovalStatus() != WorkerApprovalStatus.APPROVED
                || worker.getConnectionStatus() != WorkerConnectionStatus.ONLINE
                || worker.getAvailabilityStatus() != WorkerAvailabilityStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Worker must be APPROVED, ONLINE, and AVAILABLE to claim assigned execution."
            );
        }
    }
}
