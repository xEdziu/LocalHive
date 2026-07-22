package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.dto.AdminCancelExecutionRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionListResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminSelectionDiagnosticsResponseDto;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionCancelService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionCreationService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionQueryService;
import dev.adrian.goral.localhivebackend.service.work.AdminSelectionDiagnosticsService;
import dev.adrian.goral.localhivebackend.service.work.WorkerSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/executions")
@RequiredArgsConstructor
public class AdminExecutionController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_OFFSET = 0;

    private final AdminExecutionQueryService queryService;
    private final AdminExecutionCreationService creationService;
    private final AdminSelectionDiagnosticsService diagnosticsService;
    private final AdminExecutionCancelService cancelService;

    @PostMapping
    public ResponseEntity<AdminCreateExecutionResponseDto> createExecution(
            @RequestBody AdminCreateExecutionRequestDto request
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(creationService.createExecution(request));
        } catch (WorkerSelectionService.NoEligibleWorkerException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/selection-diagnostics")
    public ResponseEntity<AdminSelectionDiagnosticsResponseDto> selectionDiagnostics(
            @RequestBody AdminCreateExecutionRequestDto request
    ) {
        try {
            return ResponseEntity.ok(diagnosticsService.diagnose(request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{executionId}/cancel")
    public ResponseEntity<AdminExecutionDetailResponseDto> cancelExecution(
            @PathVariable UUID executionId,
            @RequestBody(required = false) AdminCancelExecutionRequestDto request
    ) {
        try {
            cancelService.cancelExecution(
                    executionId,
                    request == null ? null : request.reason(),
                    LocalDateTime.now()
            );
            AdminExecutionDetailResponseDto response = queryService.getExecution(executionId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Execution not found."
                    ));
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<AdminExecutionListResponseDto> listExecutions(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workerId
    ) {
        try {
            return ResponseEntity.ok(queryService.listExecutions(
                    parseLimit(limit),
                    parseOffset(offset),
                    parseStatus(status),
                    parseWorkerId(workerId)
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<AdminExecutionDetailResponseDto> getExecution(@PathVariable UUID executionId) {
        return queryService.getExecution(executionId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found."));
    }

    private static int parseLimit(String rawLimit) {
        if (rawLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (rawLimit.isBlank()) {
            throw new IllegalArgumentException("limit must not be blank.");
        }

        try {
            return Integer.parseInt(rawLimit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be a whole number.");
        }
    }

    private static int parseOffset(String rawOffset) {
        if (rawOffset == null) {
            return DEFAULT_OFFSET;
        }
        if (rawOffset.isBlank()) {
            throw new IllegalArgumentException("offset must not be blank.");
        }

        try {
            return Integer.parseInt(rawOffset);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("offset must be a whole number.");
        }
    }

    private static WorkExecutionStatus parseStatus(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        if (rawStatus.isBlank()) {
            throw new IllegalArgumentException("status must not be blank.");
        }

        try {
            return WorkExecutionStatus.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown execution status: " + rawStatus.trim());
        }
    }

    private static UUID parseWorkerId(String rawWorkerId) {
        if (rawWorkerId == null) {
            return null;
        }
        if (rawWorkerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank.");
        }

        try {
            return UUID.fromString(rawWorkerId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("workerId must be a valid UUID.");
        }
    }
}
