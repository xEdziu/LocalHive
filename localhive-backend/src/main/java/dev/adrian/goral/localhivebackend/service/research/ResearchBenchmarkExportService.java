package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenarioStatus;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkEventResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkMeasurementResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioResponseDto;
import dev.adrian.goral.localhivebackend.dto.ResearchBenchmarkDatasetDto;
import dev.adrian.goral.localhivebackend.dto.ResearchBenchmarkExportManifestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResearchBenchmarkExportService {

    private static final String SCHEMA_VERSION = "1.0";

    private static final List<String> SUMMARY_CSV_HEADERS = List.of(
            "benchmarkRunId",
            "displayName",
            "status",
            "protocol",
            "workloadId",
            "operation",
            "scenarioCount",
            "completedScenarioCount",
            "failedScenarioCount",
            "skippedScenarioCount",
            "avgRequestLatencyMs",
            "avgPayloadResponseBytes",
            "totalErrorCount"
    );

    private static final List<String> SCENARIOS_CSV_HEADERS = List.of(
            "benchmarkRunId",
            "scenarioId",
            "scenarioIndex",
            "displayName",
            "workloadId",
            "protocol",
            "operation",
            "dataTransferMode",
            "payloadFormat",
            "status",
            "createdAt",
            "startedAt",
            "completedAt",
            "executionId",
            "executionGroupId",
            "errorCode",
            "errorMessage",
            "notes"
    );

    private static final List<String> MEASUREMENTS_CSV_HEADERS = List.of(
            "benchmarkRunId",
            "scenarioId",
            "measurementId",
            "type",
            "valueNumeric",
            "unit",
            "recordedAt",
            "notes"
    );

    private static final List<String> EVENTS_CSV_HEADERS = List.of(
            "benchmarkRunId",
            "scenarioId",
            "eventId",
            "type",
            "occurredAt",
            "message",
            "metadataJson",
            "relatedExecutionId",
            "relatedExecutionGroupId"
    );

    private final BenchmarkRunQueryService queryService;

    @Transactional(readOnly = true)
    public ResearchBenchmarkExportManifestDto manifest(UUID benchmarkRunId) {
        AdminBenchmarkRunResponseDto run = requireRun(benchmarkRunId);
        String basePath = "/api/admin/research/benchmark-runs/" + run.benchmarkRunId() + "/exports";
        return new ResearchBenchmarkExportManifestDto(
                run.benchmarkRunId(),
                LocalDateTime.now(),
                List.of(
                        export("dataset-json", "JSON", basePath + "/dataset.json",
                                "Complete benchmark dataset for thesis analysis."),
                        export("summary-csv", "CSV", basePath + "/summary.csv",
                                "Aggregated protocol/workload summary."),
                        export("scenarios-csv", "CSV", basePath + "/scenarios.csv",
                                "Scenario-level data."),
                        export("measurements-csv", "CSV", basePath + "/measurements.csv",
                                "Raw measurement data."),
                        export("events-csv", "CSV", basePath + "/events.csv",
                                "Benchmark event timeline.")
                )
        );
    }

    @Transactional(readOnly = true)
    public ResearchBenchmarkDatasetDto dataset(UUID benchmarkRunId) {
        ExportData data = loadData(benchmarkRunId);
        return new ResearchBenchmarkDatasetDto(
                SCHEMA_VERSION,
                data.generatedAt(),
                runDto(data.run()),
                data.scenarios().stream()
                        .map(ResearchBenchmarkExportService::scenarioDto)
                        .toList(),
                data.measurements().stream()
                        .map(ResearchBenchmarkExportService::measurementDto)
                        .toList(),
                data.events().stream()
                        .map(ResearchBenchmarkExportService::eventDto)
                        .toList(),
                summaryDto(data)
        );
    }

    @Transactional(readOnly = true)
    public String summaryCsv(UUID benchmarkRunId) {
        ExportData data = loadData(benchmarkRunId);
        List<List<Object>> rows = summaryRows(data).stream()
                .map(row -> row(
                        data.run().benchmarkRunId(),
                        data.run().displayName(),
                        data.run().status(),
                        row.key().protocol(),
                        row.key().workloadId(),
                        row.key().operation(),
                        row.values().scenarioCount(),
                        row.values().completedScenarioCount(),
                        row.values().failedScenarioCount(),
                        row.values().skippedScenarioCount(),
                        row.values().avgRequestLatencyMs(),
                        row.values().avgPayloadResponseBytes(),
                        row.values().errorCount()
                ))
                .toList();
        return ResearchCsvWriter.write(SUMMARY_CSV_HEADERS, rows);
    }

    @Transactional(readOnly = true)
    public String scenariosCsv(UUID benchmarkRunId) {
        ExportData data = loadData(benchmarkRunId);
        List<List<Object>> rows = data.scenarios().stream()
                .map(scenario -> row(
                        scenario.benchmarkRunId(),
                        scenario.benchmarkScenarioId(),
                        scenario.scenarioIndex(),
                        scenario.displayName(),
                        scenario.workloadId(),
                        scenario.protocol(),
                        scenario.operation(),
                        scenario.dataTransferMode(),
                        scenario.payloadFormat(),
                        scenario.status(),
                        scenario.createdAt(),
                        scenario.startedAt(),
                        scenario.completedAt(),
                        scenario.executionId(),
                        scenario.executionGroupId(),
                        scenario.errorCode(),
                        scenario.errorMessage(),
                        scenario.notes()
                ))
                .toList();
        return ResearchCsvWriter.write(SCENARIOS_CSV_HEADERS, rows);
    }

    @Transactional(readOnly = true)
    public String measurementsCsv(UUID benchmarkRunId) {
        ExportData data = loadData(benchmarkRunId);
        List<List<Object>> rows = data.measurements().stream()
                .map(measurement -> row(
                        measurement.benchmarkRunId(),
                        measurement.benchmarkScenarioId(),
                        measurement.benchmarkMeasurementId(),
                        measurement.type(),
                        measurement.valueNumeric(),
                        measurement.unit(),
                        measurement.recordedAt(),
                        measurement.notes()
                ))
                .toList();
        return ResearchCsvWriter.write(MEASUREMENTS_CSV_HEADERS, rows);
    }

    @Transactional(readOnly = true)
    public String eventsCsv(UUID benchmarkRunId) {
        ExportData data = loadData(benchmarkRunId);
        List<List<Object>> rows = data.events().stream()
                .map(event -> row(
                        event.benchmarkRunId(),
                        event.benchmarkScenarioId(),
                        event.benchmarkEventId(),
                        event.type(),
                        event.occurredAt(),
                        event.message(),
                        event.metadataJson(),
                        event.relatedExecutionId(),
                        event.relatedExecutionGroupId()
                ))
                .toList();
        return ResearchCsvWriter.write(EVENTS_CSV_HEADERS, rows);
    }

    private ExportData loadData(UUID benchmarkRunId) {
        AdminBenchmarkRunResponseDto run = requireRun(benchmarkRunId);
        return new ExportData(
                run,
                queryService.listScenarios(run.benchmarkRunId()),
                queryService.listMeasurements(run.benchmarkRunId(), null, null),
                queryService.listEvents(run.benchmarkRunId()),
                LocalDateTime.now()
        );
    }

    private AdminBenchmarkRunResponseDto requireRun(UUID benchmarkRunId) {
        UUID validBenchmarkRunId = Objects.requireNonNull(
                benchmarkRunId,
                "benchmarkRunId must not be null."
        );
        return queryService.getRun(validBenchmarkRunId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark run not found."));
    }

    private static ResearchBenchmarkExportManifestDto.AvailableExportDto export(
            String name,
            String format,
            String path,
            String description
    ) {
        return new ResearchBenchmarkExportManifestDto.AvailableExportDto(name, format, path, description);
    }

    private static ResearchBenchmarkDatasetDto.BenchmarkRunDto runDto(AdminBenchmarkRunResponseDto run) {
        return new ResearchBenchmarkDatasetDto.BenchmarkRunDto(
                run.benchmarkRunId(),
                run.displayName(),
                run.description(),
                run.status(),
                run.createdAt(),
                run.startedAt(),
                run.completedAt(),
                run.tags(),
                run.notes()
        );
    }

    private static ResearchBenchmarkDatasetDto.ScenarioDto scenarioDto(AdminBenchmarkScenarioResponseDto scenario) {
        return new ResearchBenchmarkDatasetDto.ScenarioDto(
                scenario.benchmarkScenarioId(),
                scenario.benchmarkRunId(),
                scenario.scenarioIndex(),
                scenario.displayName(),
                scenario.workloadId(),
                scenario.protocol(),
                scenario.operation(),
                scenario.dataTransferMode(),
                scenario.payloadFormat(),
                scenario.status(),
                scenario.createdAt(),
                scenario.startedAt(),
                scenario.completedAt(),
                scenario.executionId(),
                scenario.executionGroupId(),
                scenario.errorCode(),
                scenario.errorMessage(),
                scenario.notes()
        );
    }

    private static ResearchBenchmarkDatasetDto.MeasurementDto measurementDto(
            AdminBenchmarkMeasurementResponseDto measurement
    ) {
        return new ResearchBenchmarkDatasetDto.MeasurementDto(
                measurement.benchmarkMeasurementId(),
                measurement.benchmarkRunId(),
                measurement.benchmarkScenarioId(),
                measurement.type(),
                measurement.valueNumeric(),
                measurement.unit(),
                measurement.recordedAt(),
                measurement.notes()
        );
    }

    private static ResearchBenchmarkDatasetDto.EventDto eventDto(AdminBenchmarkEventResponseDto event) {
        return new ResearchBenchmarkDatasetDto.EventDto(
                event.benchmarkEventId(),
                event.benchmarkRunId(),
                event.benchmarkScenarioId(),
                event.type(),
                event.occurredAt(),
                event.message(),
                event.metadataJson(),
                event.relatedExecutionId(),
                event.relatedExecutionGroupId()
        );
    }

    private static ResearchBenchmarkDatasetDto.SummaryDto summaryDto(ExportData data) {
        return new ResearchBenchmarkDatasetDto.SummaryDto(
                data.scenarios().size(),
                data.measurements().size(),
                data.events().size(),
                aggregateBy(
                        data,
                        AdminBenchmarkScenarioResponseDto::protocol,
                        Comparator.comparing(protocol -> protocol.name()),
                        (protocol, values) -> new ResearchBenchmarkDatasetDto.ProtocolSummaryDto(
                                protocol,
                                values.scenarioCount(),
                                values.completedScenarioCount(),
                                values.failedScenarioCount(),
                                values.skippedScenarioCount(),
                                values.avgRequestLatencyMs(),
                                values.avgPayloadResponseBytes(),
                                values.errorCount()
                        )
                ),
                aggregateBy(
                        data,
                        AdminBenchmarkScenarioResponseDto::operation,
                        Comparator.comparing(operation -> operation.name()),
                        (operation, values) -> new ResearchBenchmarkDatasetDto.OperationSummaryDto(
                                operation,
                                values.scenarioCount(),
                                values.completedScenarioCount(),
                                values.failedScenarioCount(),
                                values.skippedScenarioCount(),
                                values.avgRequestLatencyMs(),
                                values.avgPayloadResponseBytes(),
                                values.errorCount()
                        )
                ),
                aggregateBy(
                        data,
                        AdminBenchmarkScenarioResponseDto::workloadId,
                        Comparator.naturalOrder(),
                        (workloadId, values) -> new ResearchBenchmarkDatasetDto.WorkloadSummaryDto(
                                workloadId,
                                values.scenarioCount(),
                                values.completedScenarioCount(),
                                values.failedScenarioCount(),
                                values.skippedScenarioCount(),
                                values.avgRequestLatencyMs(),
                                values.avgPayloadResponseBytes(),
                                values.errorCount()
                        )
                )
        );
    }

    private static <K, R> List<R> aggregateBy(ExportData data,
                                              Function<AdminBenchmarkScenarioResponseDto, K> classifier,
                                              Comparator<K> keyComparator,
                                              SummaryFactory<K, R> factory) {
        return data.scenarios().stream()
                .collect(Collectors.groupingBy(classifier))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(keyComparator))
                .map(entry -> factory.create(
                        entry.getKey(),
                        summarize(entry.getValue(), data.measurements())
                ))
                .toList();
    }

    private static List<SummaryRow> summaryRows(ExportData data) {
        return data.scenarios().stream()
                .collect(Collectors.groupingBy(scenario -> new SummaryKey(
                        scenario.protocol(),
                        scenario.workloadId(),
                        scenario.operation()
                )))
                .entrySet()
                .stream()
                .map(entry -> new SummaryRow(entry.getKey(), summarize(entry.getValue(), data.measurements())))
                .sorted(Comparator
                        .comparing((SummaryRow row) -> row.key().protocol().name())
                        .thenComparing(row -> row.key().workloadId())
                        .thenComparing(row -> row.key().operation().name()))
                .toList();
    }

    private static SummaryValues summarize(List<AdminBenchmarkScenarioResponseDto> scenarios,
                                           List<AdminBenchmarkMeasurementResponseDto> allMeasurements) {
        Set<UUID> scenarioIds = scenarios.stream()
                .map(AdminBenchmarkScenarioResponseDto::benchmarkScenarioId)
                .collect(Collectors.toSet());
        List<AdminBenchmarkMeasurementResponseDto> measurements = allMeasurements.stream()
                .filter(measurement -> scenarioIds.contains(measurement.benchmarkScenarioId()))
                .toList();

        return new SummaryValues(
                scenarios.size(),
                countStatus(scenarios, BenchmarkScenarioStatus.COMPLETED),
                countStatus(scenarios, BenchmarkScenarioStatus.FAILED),
                countStatus(scenarios, BenchmarkScenarioStatus.SKIPPED),
                average(measurements, BenchmarkMeasurementType.REQUEST_LATENCY_MS),
                average(measurements, BenchmarkMeasurementType.PAYLOAD_RESPONSE_BYTES),
                sum(measurements, BenchmarkMeasurementType.ERROR_COUNT)
        );
    }

    private static long countStatus(List<AdminBenchmarkScenarioResponseDto> scenarios,
                                    BenchmarkScenarioStatus status) {
        return scenarios.stream()
                .filter(scenario -> scenario.status() == status)
                .count();
    }

    private static BigDecimal average(List<AdminBenchmarkMeasurementResponseDto> measurements,
                                      BenchmarkMeasurementType type) {
        List<BigDecimal> values = measurements.stream()
                .filter(measurement -> measurement.type() == type)
                .map(AdminBenchmarkMeasurementResponseDto::valueNumeric)
                .toList();
        if (values.isEmpty()) {
            return null;
        }

        return normalize(values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal sum(List<AdminBenchmarkMeasurementResponseDto> measurements,
                                  BenchmarkMeasurementType type) {
        List<BigDecimal> values = measurements.stream()
                .filter(measurement -> measurement.type() == type)
                .map(AdminBenchmarkMeasurementResponseDto::valueNumeric)
                .toList();
        if (values.isEmpty()) {
            return null;
        }

        return normalize(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return BigDecimal.ZERO.compareTo(normalized) == 0 ? BigDecimal.ZERO : normalized;
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    private record ExportData(
            AdminBenchmarkRunResponseDto run,
            List<AdminBenchmarkScenarioResponseDto> scenarios,
            List<AdminBenchmarkMeasurementResponseDto> measurements,
            List<AdminBenchmarkEventResponseDto> events,
            LocalDateTime generatedAt
    ) {
    }

    private record SummaryKey(ResearchProtocol protocol, String workloadId, ResearchOperation operation) {
    }

    private record SummaryValues(
            long scenarioCount,
            long completedScenarioCount,
            long failedScenarioCount,
            long skippedScenarioCount,
            BigDecimal avgRequestLatencyMs,
            BigDecimal avgPayloadResponseBytes,
            BigDecimal errorCount
    ) {
    }

    private record SummaryRow(SummaryKey key, SummaryValues values) {
    }

    private interface SummaryFactory<K, R> {
        R create(K key, SummaryValues values);
    }
}
