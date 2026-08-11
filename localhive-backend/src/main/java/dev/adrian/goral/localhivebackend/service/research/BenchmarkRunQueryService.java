package dev.adrian.goral.localhivebackend.service.research;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEvent;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurement;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRun;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenario;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkEventResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkMeasurementResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioResponseDto;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkEventRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkMeasurementRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkRunRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkScenarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BenchmarkRunQueryService {

    private final BenchmarkRunRepository runRepository;
    private final BenchmarkScenarioRepository scenarioRepository;
    private final BenchmarkMeasurementRepository measurementRepository;
    private final BenchmarkEventRepository eventRepository;

    @Transactional(readOnly = true)
    public List<AdminBenchmarkRunResponseDto> listRuns(BenchmarkRunStatus status) {
        return runRepository.findAdminRuns(status).stream()
                .map(this::toRunResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminBenchmarkRunResponseDto> getRun(UUID benchmarkRunId) {
        UUID validBenchmarkRunId = Objects.requireNonNull(
                benchmarkRunId,
                "benchmarkRunId must not be null."
        );
        return runRepository.findById(validBenchmarkRunId)
                .map(this::toRunResponse);
    }

    @Transactional(readOnly = true)
    public List<AdminBenchmarkScenarioResponseDto> listScenarios(UUID benchmarkRunId) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        return scenarioRepository.findByBenchmarkRun_IdOrderByScenarioIndexAsc(run.getId()).stream()
                .map(BenchmarkRunQueryService::toScenarioResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminBenchmarkMeasurementResponseDto> listMeasurements(
            UUID benchmarkRunId,
            UUID scenarioId,
            BenchmarkMeasurementType type
    ) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        if (scenarioId != null) {
            requireScenarioInRun(run, scenarioId);
        }

        return measurementRepository.findAdminMeasurements(run.getId(), scenarioId, type).stream()
                .map(BenchmarkRunQueryService::toMeasurementResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminBenchmarkEventResponseDto> listEvents(UUID benchmarkRunId) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        return eventRepository.findByBenchmarkRun_IdOrderByOccurredAtAscIdAsc(run.getId()).stream()
                .map(BenchmarkRunQueryService::toEventResponse)
                .toList();
    }

    AdminBenchmarkRunResponseDto toRunResponse(BenchmarkRun run) {
        UUID benchmarkRunId = run.getId();
        return new AdminBenchmarkRunResponseDto(
                benchmarkRunId,
                run.getDisplayName(),
                run.getDescription(),
                run.getStatus(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreatedBy(),
                tagsFromJson(run.getTags()),
                run.getNotes(),
                scenarioRepository.countByBenchmarkRun_Id(benchmarkRunId),
                measurementRepository.countByBenchmarkRun_Id(benchmarkRunId),
                eventRepository.countByBenchmarkRun_Id(benchmarkRunId)
        );
    }

    static AdminBenchmarkScenarioResponseDto toScenarioResponse(BenchmarkScenario scenario) {
        return new AdminBenchmarkScenarioResponseDto(
                scenario.getId(),
                scenario.getBenchmarkRun().getId(),
                scenario.getScenarioIndex(),
                scenario.getDisplayName(),
                scenario.getWorkloadId(),
                scenario.getProtocol(),
                scenario.getOperation(),
                scenario.getDataTransferMode(),
                scenario.getPayloadFormat(),
                scenario.getStatus(),
                scenario.getCreatedAt(),
                scenario.getStartedAt(),
                scenario.getCompletedAt(),
                scenario.getExecutionId(),
                scenario.getExecutionGroupId(),
                scenario.getErrorCode(),
                scenario.getErrorMessage(),
                scenario.getNotes()
        );
    }

    static AdminBenchmarkMeasurementResponseDto toMeasurementResponse(BenchmarkMeasurement measurement) {
        BenchmarkScenario scenario = measurement.getBenchmarkScenario();
        return new AdminBenchmarkMeasurementResponseDto(
                measurement.getId(),
                measurement.getBenchmarkRun().getId(),
                scenario == null ? null : scenario.getId(),
                measurement.getType(),
                measurement.getValueNumeric(),
                measurement.getUnit(),
                measurement.getRecordedAt(),
                measurement.getNotes()
        );
    }

    static AdminBenchmarkEventResponseDto toEventResponse(BenchmarkEvent event) {
        BenchmarkScenario scenario = event.getBenchmarkScenario();
        return new AdminBenchmarkEventResponseDto(
                event.getId(),
                event.getBenchmarkRun().getId(),
                scenario == null ? null : scenario.getId(),
                event.getType(),
                event.getOccurredAt(),
                event.getMessage(),
                event.getMetadataJson(),
                event.getRelatedExecutionId(),
                event.getRelatedExecutionGroupId()
        );
    }

    private BenchmarkRun requireRun(UUID benchmarkRunId) {
        UUID validBenchmarkRunId = Objects.requireNonNull(
                benchmarkRunId,
                "benchmarkRunId must not be null."
        );
        return runRepository.findById(validBenchmarkRunId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark run not found."));
    }

    private void requireScenarioInRun(BenchmarkRun run, UUID scenarioId) {
        BenchmarkScenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark scenario not found."));
        if (!scenario.belongsTo(run)) {
            throw new IllegalArgumentException("scenarioId must belong to the benchmark run.");
        }
    }

    private static List<String> tagsFromJson(JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (tag != null && tag.isTextual()) {
                result.add(tag.asText());
            }
        }
        return List.copyOf(result);
    }
}
