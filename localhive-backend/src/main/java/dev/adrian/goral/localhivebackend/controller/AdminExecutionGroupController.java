package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.dto.AdminCancelExecutionRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionGroupRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupActivityResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupChildExecutionResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupArtifactsResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupListResponseDto;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupActivityStreamCommand;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupActivityStreamService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupControlService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupCreationService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/execution-groups")
@RequiredArgsConstructor
public class AdminExecutionGroupController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_OFFSET = 0;

    private final AdminExecutionGroupCreationService creationService;
    private final AdminExecutionGroupQueryService queryService;
    private final AdminExecutionGroupActivityStreamService activityStreamService;
    private final AdminExecutionGroupControlService controlService;

    @PostMapping
    public ResponseEntity<AdminExecutionGroupDetailResponseDto> createGroup(
            @RequestBody AdminCreateExecutionGroupRequestDto request
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(creationService.createExecutionGroup(request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<AdminExecutionGroupListResponseDto> listGroups(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset,
            @RequestParam(required = false) String status
    ) {
        try {
            return ResponseEntity.ok(queryService.listGroups(
                    parseLimit(limit),
                    parseOffset(offset),
                    parseStatus(status)
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{executionGroupId}")
    public ResponseEntity<AdminExecutionGroupDetailResponseDto> getGroup(@PathVariable UUID executionGroupId) {
        return queryService.getGroup(executionGroupId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution group not found."));
    }

    @GetMapping("/{executionGroupId}/executions")
    public ResponseEntity<List<AdminExecutionGroupChildExecutionResponseDto>> listChildExecutions(
            @PathVariable UUID executionGroupId
    ) {
        return queryService.listChildExecutions(executionGroupId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution group not found."));
    }

    @GetMapping("/{executionGroupId}/activity")
    public ResponseEntity<AdminExecutionGroupActivityResponseDto> getGroupActivity(
            @PathVariable UUID executionGroupId
    ) {
        return queryService.getGroupActivity(executionGroupId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution group not found."));
    }

    @GetMapping(value = "/{executionGroupId}/activity/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGroupActivity(
            @PathVariable UUID executionGroupId,
            @RequestParam(required = false) String pollIntervalMs,
            @RequestParam(required = false) String heartbeatIntervalMs,
            @RequestParam(required = false) String closeOnTerminal,
            @RequestParam(required = false) String maxEvents
    ) {
        try {
            return activityStreamService.streamGroupActivity(new AdminExecutionGroupActivityStreamCommand(
                    executionGroupId,
                    parseOptionalInt(
                            pollIntervalMs,
                            AdminExecutionGroupActivityStreamService.DEFAULT_POLL_INTERVAL_MS,
                            "pollIntervalMs"
                    ),
                    parseOptionalInt(
                            heartbeatIntervalMs,
                            AdminExecutionGroupActivityStreamService.DEFAULT_HEARTBEAT_INTERVAL_MS,
                            "heartbeatIntervalMs"
                    ),
                    parseOptionalBoolean(closeOnTerminal, false, "closeOnTerminal"),
                    parseOptionalNullableInt(maxEvents, "maxEvents")
            ));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{executionGroupId}/artifacts")
    public ResponseEntity<AdminExecutionGroupArtifactsResponseDto> listGroupArtifacts(
            @PathVariable UUID executionGroupId
    ) {
        return queryService.listGroupArtifacts(executionGroupId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution group not found."));
    }

    @PostMapping("/{executionGroupId}/cancel")
    public ResponseEntity<AdminExecutionGroupDetailResponseDto> cancelGroup(
            @PathVariable UUID executionGroupId,
            @RequestBody(required = false) AdminCancelExecutionRequestDto request
    ) {
        try {
            controlService.cancelGroup(
                    executionGroupId,
                    request == null ? null : request.reason(),
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(groupDetail(executionGroupId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{executionGroupId}/reconcile")
    public ResponseEntity<AdminExecutionGroupDetailResponseDto> reconcileGroup(
            @PathVariable UUID executionGroupId
    ) {
        try {
            controlService.reconcileGroup(executionGroupId, LocalDateTime.now());
            return ResponseEntity.ok(groupDetail(executionGroupId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private AdminExecutionGroupDetailResponseDto groupDetail(UUID executionGroupId) {
        return queryService.getGroup(executionGroupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution group not found."));
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

    private static ExecutionGroupStatus parseStatus(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        if (rawStatus.isBlank()) {
            throw new IllegalArgumentException("status must not be blank.");
        }

        try {
            return ExecutionGroupStatus.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown execution group status: " + rawStatus.trim());
        }
    }

    private static int parseOptionalInt(String rawValue, int defaultValue, String fieldName) {
        Integer parsed = parseOptionalNullableInt(rawValue, fieldName);
        return parsed == null ? defaultValue : parsed;
    }

    private static Integer parseOptionalNullableInt(String rawValue, String fieldName) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a whole number.");
        }
    }

    private static boolean parseOptionalBoolean(String rawValue, boolean defaultValue, String fieldName) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }

        throw new IllegalArgumentException(fieldName + " must be true or false.");
    }
}
