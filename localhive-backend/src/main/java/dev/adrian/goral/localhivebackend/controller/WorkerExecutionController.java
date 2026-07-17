package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.dto.WorkerExecutionClaimResponseDto;
import dev.adrian.goral.localhivebackend.dto.WorkerExecutionFailureRequestDto;
import dev.adrian.goral.localhivebackend.dto.WorkerExecutionLeaseRenewalResponseDto;
import dev.adrian.goral.localhivebackend.dto.WorkerExecutionStatusResponseDto;
import dev.adrian.goral.localhivebackend.service.work.ClaimedExecution;
import dev.adrian.goral.localhivebackend.service.work.ExecutionLeaseException;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionClaimService;
import dev.adrian.goral.localhivebackend.service.work.WorkerExecutionReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/workers/{workerId}")
@RequiredArgsConstructor
public class WorkerExecutionController {

    private static final String EXECUTION_LEASE_HEADER = "X-EXECUTION-LEASE";

    private final WorkExecutionClaimService claimService;
    private final WorkerExecutionReportService reportService;

    @PostMapping("/assigned-executions/claim-next")
    public ResponseEntity<WorkerExecutionClaimResponseDto> claimNextAssignedExecution(@PathVariable UUID workerId) {
        try {
            Optional<ClaimedExecution> claimedExecution =
                    claimService.claimNextAssignedExecution(workerId, LocalDateTime.now());
            return claimedExecution
                    .map(value -> ResponseEntity.ok(WorkerExecutionClaimResponseDto.from(value)))
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/executions/{executionId}/running")
    public ResponseEntity<WorkerExecutionStatusResponseDto> reportRunning(
            @PathVariable UUID workerId,
            @PathVariable UUID executionId,
            @RequestHeader(EXECUTION_LEASE_HEADER) String rawLeaseToken
    ) {
        try {
            WorkExecution execution = reportService.reportRunning(
                    workerId,
                    executionId,
                    rawLeaseToken,
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(new WorkerExecutionStatusResponseDto(execution.getStatus().name()));
        } catch (ExecutionLeaseException e) {
            throw leaseResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/executions/{executionId}/succeeded")
    public ResponseEntity<WorkerExecutionStatusResponseDto> reportSucceeded(
            @PathVariable UUID workerId,
            @PathVariable UUID executionId,
            @RequestHeader(EXECUTION_LEASE_HEADER) String rawLeaseToken
    ) {
        try {
            WorkExecution execution = reportService.reportSucceeded(
                    workerId,
                    executionId,
                    rawLeaseToken,
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(new WorkerExecutionStatusResponseDto(execution.getStatus().name()));
        } catch (ExecutionLeaseException e) {
            throw leaseResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/executions/{executionId}/failed")
    public ResponseEntity<WorkerExecutionStatusResponseDto> reportFailed(
            @PathVariable UUID workerId,
            @PathVariable UUID executionId,
            @RequestHeader(EXECUTION_LEASE_HEADER) String rawLeaseToken,
            @Valid @RequestBody WorkerExecutionFailureRequestDto request
    ) {
        try {
            WorkExecution execution = reportService.reportFailed(
                    workerId,
                    executionId,
                    rawLeaseToken,
                    request.failureCode(),
                    request.failureMessage(),
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(new WorkerExecutionStatusResponseDto(execution.getStatus().name()));
        } catch (ExecutionLeaseException e) {
            throw leaseResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/executions/{executionId}/lease/renew")
    public ResponseEntity<WorkerExecutionLeaseRenewalResponseDto> renewLease(
            @PathVariable UUID workerId,
            @PathVariable UUID executionId,
            @RequestHeader(EXECUTION_LEASE_HEADER) String rawLeaseToken
    ) {
        try {
            ExecutionAssignment assignment = reportService.renewLease(
                    workerId,
                    executionId,
                    rawLeaseToken,
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(new WorkerExecutionLeaseRenewalResponseDto(assignment.getLeaseExpiresAt()));
        } catch (ExecutionLeaseException e) {
            throw leaseResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static ResponseStatusException leaseResponse(ExecutionLeaseException exception) {
        HttpStatus status = switch (exception.getReason()) {
            case EXPIRED, INVALID_STATUS -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.FORBIDDEN;
        };
        return new ResponseStatusException(status, exception.getMessage());
    }
}
