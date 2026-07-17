package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExecutionLeaseValidationService {

    private final ExecutionAssignmentRepository assignmentRepository;
    private final ExecutionLeaseTokenService leaseTokenService;

    public ExecutionAssignment validateLease(UUID workerId,
                                             UUID executionId,
                                             String rawLeaseToken,
                                             LocalDateTime now,
                                             Set<WorkExecutionStatus> allowedStatuses) {
        LocalDateTime validationTime = requireTimestamp(now, "now");
        Set<WorkExecutionStatus> validAllowedStatuses = requireAllowedStatuses(allowedStatuses);
        if (rawLeaseToken == null || rawLeaseToken.isBlank()) {
            throw ExecutionLeaseException.invalid("Execution lease is invalid.");
        }

        ExecutionAssignment assignment = assignmentRepository.findByExecution_IdAndWorker_Id(executionId, workerId)
                .orElseThrow(() -> ExecutionLeaseException.invalid("Execution lease is invalid."));

        if (assignment.getLeaseTokenHash() == null || assignment.getLeaseTokenHash().isBlank()
                || assignment.getLeaseExpiresAt() == null) {
            throw ExecutionLeaseException.invalid("Execution lease is invalid.");
        }
        if (!leaseTokenService.matches(rawLeaseToken, assignment.getLeaseTokenHash())) {
            throw ExecutionLeaseException.invalid("Execution lease is invalid.");
        }
        if (!assignment.getLeaseExpiresAt().isAfter(validationTime)) {
            throw ExecutionLeaseException.expired("Execution lease has expired.");
        }

        WorkExecutionStatus status = assignment.getExecution().getStatus();
        if (!validAllowedStatuses.contains(status)) {
            throw ExecutionLeaseException.invalidStatus(
                    "Execution status does not allow this operation: " + status + "."
            );
        }

        return assignment;
    }

    private static LocalDateTime requireTimestamp(LocalDateTime timestamp, String fieldName) {
        if (timestamp == null) {
            throw new IllegalArgumentException(fieldName + " must not be null.");
        }

        return timestamp;
    }

    private static Set<WorkExecutionStatus> requireAllowedStatuses(Set<WorkExecutionStatus> allowedStatuses) {
        if (allowedStatuses == null || allowedStatuses.isEmpty()) {
            throw new IllegalArgumentException("allowedStatuses must not be empty.");
        }

        return EnumSet.copyOf(allowedStatuses);
    }
}
