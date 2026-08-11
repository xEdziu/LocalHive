package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkEventCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkEventResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkMeasurementCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkMeasurementResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunFailRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioFailRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioResponseDto;
import dev.adrian.goral.localhivebackend.service.research.BenchmarkRunQueryService;
import dev.adrian.goral.localhivebackend.service.research.BenchmarkRunRecorderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/research/benchmark-runs")
@RequiredArgsConstructor
public class AdminResearchBenchmarkRecorderController {

    private final BenchmarkRunRecorderService recorderService;
    private final BenchmarkRunQueryService queryService;

    @PostMapping
    public ResponseEntity<AdminBenchmarkRunResponseDto> createRun(
            @RequestBody AdminBenchmarkRunCreateRequestDto request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(recorderService.createRun(
                    request,
                    authentication == null ? null : authentication.getName()
            ));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<AdminBenchmarkRunResponseDto>> listRuns(
            @RequestParam(required = false) String status
    ) {
        try {
            return ResponseEntity.ok(queryService.listRuns(parseRunStatus(status)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{benchmarkRunId}")
    public ResponseEntity<AdminBenchmarkRunResponseDto> getRun(@PathVariable UUID benchmarkRunId) {
        return queryService.getRun(benchmarkRunId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benchmark run not found."));
    }

    @PostMapping("/{benchmarkRunId}/start")
    public ResponseEntity<AdminBenchmarkRunResponseDto> startRun(@PathVariable UUID benchmarkRunId) {
        try {
            return ResponseEntity.ok(recorderService.startRun(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/complete")
    public ResponseEntity<AdminBenchmarkRunResponseDto> completeRun(@PathVariable UUID benchmarkRunId) {
        try {
            return ResponseEntity.ok(recorderService.completeRun(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/fail")
    public ResponseEntity<AdminBenchmarkRunResponseDto> failRun(
            @PathVariable UUID benchmarkRunId,
            @RequestBody(required = false) AdminBenchmarkRunFailRequestDto request
    ) {
        try {
            return ResponseEntity.ok(recorderService.failRun(benchmarkRunId, request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/scenarios")
    public ResponseEntity<AdminBenchmarkScenarioResponseDto> addScenario(
            @PathVariable UUID benchmarkRunId,
            @RequestBody AdminBenchmarkScenarioCreateRequestDto request
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(recorderService.addScenario(benchmarkRunId, request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{benchmarkRunId}/scenarios")
    public ResponseEntity<List<AdminBenchmarkScenarioResponseDto>> listScenarios(@PathVariable UUID benchmarkRunId) {
        try {
            return ResponseEntity.ok(queryService.listScenarios(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/scenarios/{scenarioId}/start")
    public ResponseEntity<AdminBenchmarkScenarioResponseDto> startScenario(
            @PathVariable UUID benchmarkRunId,
            @PathVariable UUID scenarioId
    ) {
        try {
            return ResponseEntity.ok(recorderService.startScenario(benchmarkRunId, scenarioId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/scenarios/{scenarioId}/complete")
    public ResponseEntity<AdminBenchmarkScenarioResponseDto> completeScenario(
            @PathVariable UUID benchmarkRunId,
            @PathVariable UUID scenarioId
    ) {
        try {
            return ResponseEntity.ok(recorderService.completeScenario(benchmarkRunId, scenarioId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/scenarios/{scenarioId}/fail")
    public ResponseEntity<AdminBenchmarkScenarioResponseDto> failScenario(
            @PathVariable UUID benchmarkRunId,
            @PathVariable UUID scenarioId,
            @RequestBody(required = false) AdminBenchmarkScenarioFailRequestDto request
    ) {
        try {
            return ResponseEntity.ok(recorderService.failScenario(benchmarkRunId, scenarioId, request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/measurements")
    public ResponseEntity<AdminBenchmarkMeasurementResponseDto> recordMeasurement(
            @PathVariable UUID benchmarkRunId,
            @RequestBody AdminBenchmarkMeasurementCreateRequestDto request
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(recorderService.recordMeasurement(benchmarkRunId, request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{benchmarkRunId}/measurements")
    public ResponseEntity<List<AdminBenchmarkMeasurementResponseDto>> listMeasurements(
            @PathVariable UUID benchmarkRunId,
            @RequestParam(required = false) String scenarioId,
            @RequestParam(required = false) String type
    ) {
        try {
            return ResponseEntity.ok(queryService.listMeasurements(
                    benchmarkRunId,
                    parseUuid(scenarioId, "scenarioId"),
                    parseMeasurementType(type)
            ));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{benchmarkRunId}/events")
    public ResponseEntity<AdminBenchmarkEventResponseDto> recordEvent(
            @PathVariable UUID benchmarkRunId,
            @RequestBody AdminBenchmarkEventCreateRequestDto request
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(recorderService.recordEvent(benchmarkRunId, request));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{benchmarkRunId}/events")
    public ResponseEntity<List<AdminBenchmarkEventResponseDto>> listEvents(@PathVariable UUID benchmarkRunId) {
        try {
            return ResponseEntity.ok(queryService.listEvents(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static BenchmarkRunStatus parseRunStatus(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        if (rawStatus.isBlank()) {
            throw new IllegalArgumentException("status must not be blank.");
        }

        try {
            return BenchmarkRunStatus.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown benchmark run status: " + rawStatus.trim());
        }
    }

    private static BenchmarkMeasurementType parseMeasurementType(String rawType) {
        if (rawType == null) {
            return null;
        }
        if (rawType.isBlank()) {
            throw new IllegalArgumentException("type must not be blank.");
        }

        try {
            return BenchmarkMeasurementType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown benchmark measurement type: " + rawType.trim());
        }
    }

    private static UUID parseUuid(String rawUuid, String fieldName) {
        if (rawUuid == null) {
            return null;
        }
        if (rawUuid.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        try {
            return UUID.fromString(rawUuid.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID.");
        }
    }
}
