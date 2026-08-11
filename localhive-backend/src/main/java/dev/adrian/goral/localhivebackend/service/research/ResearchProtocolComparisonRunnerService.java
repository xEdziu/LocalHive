package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEventType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkEventCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkMeasurementCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunFailRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkRunResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioCreateRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioFailRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminBenchmarkScenarioResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminProtocolComparisonRunRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminProtocolComparisonRunResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationResponseDto;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ResearchProtocolComparisonRunnerService {

    private static final int MAX_DISPLAY_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_NOTES_LENGTH = 4000;
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 50;
    private static final int MAX_PROTOCOLS = 3;
    private static final int MIN_REPETITIONS = 1;
    private static final int MAX_REPETITIONS = 20;
    private static final Set<ResearchOperation> SUPPORTED_OPERATIONS = EnumSet.of(
            ResearchOperation.GET_GROUP_DETAIL,
            ResearchOperation.GET_GROUP_ACTIVITY,
            ResearchOperation.GET_GROUP_ARTIFACTS
    );

    private final BenchmarkRunRecorderService recorderService;
    private final BenchmarkRunQueryService queryService;
    private final ResearchWorkloadCatalogService workloadCatalogService;
    private final ResearchWorkloadCatalogValidator workloadValidator;
    private final ResearchProtocolContractValidator protocolValidator;
    private final ExecutionGroupRepository groupRepository;
    private final Map<ResearchProtocol, ResearchProtocolOperationInvoker> invokers;

    public ResearchProtocolComparisonRunnerService(
            BenchmarkRunRecorderService recorderService,
            BenchmarkRunQueryService queryService,
            ResearchWorkloadCatalogService workloadCatalogService,
            ResearchWorkloadCatalogValidator workloadValidator,
            ResearchProtocolContractValidator protocolValidator,
            ExecutionGroupRepository groupRepository,
            List<ResearchProtocolOperationInvoker> invokers
    ) {
        this.recorderService = recorderService;
        this.queryService = queryService;
        this.workloadCatalogService = workloadCatalogService;
        this.workloadValidator = workloadValidator;
        this.protocolValidator = protocolValidator;
        this.groupRepository = groupRepository;
        this.invokers = invokers.stream()
                .collect(Collectors.toUnmodifiableMap(ResearchProtocolOperationInvoker::protocol, Function.identity()));
    }

    public AdminProtocolComparisonRunResponseDto run(
            AdminProtocolComparisonRunRequestDto request,
            String createdBy
    ) {
        ValidRequest validRequest = validate(request);
        AdminBenchmarkRunResponseDto createdRun = recorderService.createRun(
                new AdminBenchmarkRunCreateRequestDto(
                        validRequest.displayName(),
                        validRequest.description(),
                        validRequest.tags(),
                        validRequest.notes()
                ),
                createdBy
        );
        UUID benchmarkRunId = createdRun.benchmarkRunId();
        Map<ResearchProtocol, ProtocolAccumulator> summary = new EnumMap<>(ResearchProtocol.class);
        validRequest.protocols().forEach(protocol -> summary.put(protocol, new ProtocolAccumulator()));

        try {
            recorderService.startRun(benchmarkRunId);
            for (ResearchProtocol protocol : validRequest.protocols()) {
                for (ResearchOperation operation : validRequest.operations()) {
                    for (int repetition = 1; repetition <= validRequest.repetitions(); repetition++) {
                        runScenario(
                                benchmarkRunId,
                                validRequest,
                                protocol,
                                operation,
                                repetition,
                                summary.get(protocol)
                        );
                    }
                }
            }
            AdminBenchmarkRunResponseDto completedRun = allCompleted(summary)
                    ? recorderService.completeRun(benchmarkRunId)
                    : recorderService.failRun(
                    benchmarkRunId,
                    new AdminBenchmarkRunFailRequestDto("One or more protocol comparison scenarios failed.")
            );
            return toResponse(completedRun, validRequest, summary);
        } catch (RuntimeException e) {
            recorderService.failRun(
                    benchmarkRunId,
                    new AdminBenchmarkRunFailRequestDto("Protocol comparison runner failed.")
            );
            throw e;
        }
    }

    private void runScenario(UUID benchmarkRunId,
                             ValidRequest request,
                             ResearchProtocol protocol,
                             ResearchOperation operation,
                             int repetition,
                             ProtocolAccumulator accumulator) {
        AdminBenchmarkScenarioResponseDto scenario = recorderService.addScenario(
                benchmarkRunId,
                new AdminBenchmarkScenarioCreateRequestDto(
                        scenarioDisplayName(protocol, operation, repetition),
                        request.workloadId(),
                        protocol,
                        operation,
                        dataTransferMode(protocol),
                        payloadFormat(protocol),
                        "M31 protocol comparison read-only scenario."
                )
        );
        UUID scenarioId = scenario.benchmarkScenarioId();
        recorderService.startScenario(benchmarkRunId, scenarioId);

        try {
            ResearchProtocolInvocationResult result = requireInvoker(protocol).invoke(
                    operation,
                    request.targetExecutionGroupId()
            );
            recordMeasurements(benchmarkRunId, scenarioId, result.requestLatencyMs(), result.payloadResponseBytes(), 0);
            recorderService.completeScenario(benchmarkRunId, scenarioId);
            accumulator.completed(result.requestLatencyMs(), result.payloadResponseBytes());
        } catch (RuntimeException e) {
            log.warn(
                    "Protocol comparison scenario failed for {} {}: {}",
                    protocol,
                    operation,
                    rootFailure(e)
            );
            recordMeasurements(benchmarkRunId, scenarioId, 0, 0, 1);
            recorderService.failScenario(
                    benchmarkRunId,
                    scenarioId,
                    new AdminBenchmarkScenarioFailRequestDto(
                            "PROTOCOL_INVOCATION_FAILED",
                            "Protocol comparison scenario failed."
                    )
            );
            accumulator.failed();
        }
    }

    private void recordMeasurements(UUID benchmarkRunId,
                                    UUID scenarioId,
                                    long requestLatencyMs,
                                    long payloadResponseBytes,
                                    long errorCount) {
        recorderService.recordMeasurement(
                benchmarkRunId,
                measurement(scenarioId, BenchmarkMeasurementType.REQUEST_LATENCY_MS, requestLatencyMs, "ms")
        );
        recorderService.recordMeasurement(
                benchmarkRunId,
                measurement(scenarioId, BenchmarkMeasurementType.PAYLOAD_RESPONSE_BYTES, payloadResponseBytes, "bytes")
        );
        recorderService.recordMeasurement(
                benchmarkRunId,
                measurement(scenarioId, BenchmarkMeasurementType.ERROR_COUNT, errorCount, "count")
        );
    }

    private static AdminBenchmarkMeasurementCreateRequestDto measurement(UUID scenarioId,
                                                                         BenchmarkMeasurementType type,
                                                                         long value,
                                                                         String unit) {
        return new AdminBenchmarkMeasurementCreateRequestDto(
                scenarioId,
                type,
                BigDecimal.valueOf(value),
                unit,
                "M31 protocol comparison runner"
        );
    }

    private AdminProtocolComparisonRunResponseDto toResponse(AdminBenchmarkRunResponseDto run,
                                                             ValidRequest request,
                                                             Map<ResearchProtocol, ProtocolAccumulator> summary) {
        recorderService.recordEvent(
                run.benchmarkRunId(),
                new AdminBenchmarkEventCreateRequestDto(
                        null,
                        BenchmarkEventType.NOTE_RECORDED,
                        "M31 protocol comparison summary recorded.",
                        "{\"kind\":\"protocol-comparison\",\"target\":\"execution-group\"}",
                        null,
                        request.targetExecutionGroupId()
                )
        );
        AdminBenchmarkRunResponseDto refreshedRun = queryService.getRun(run.benchmarkRunId())
                .orElseThrow(() -> new NoSuchElementException("Benchmark run not found."));
        return new AdminProtocolComparisonRunResponseDto(
                refreshedRun.benchmarkRunId(),
                refreshedRun.status(),
                request.targetExecutionGroupId(),
                request.workloadId(),
                request.protocols(),
                request.operations(),
                request.repetitions(),
                refreshedRun.scenarioCount(),
                refreshedRun.measurementCount(),
                refreshedRun.eventCount(),
                request.protocols()
                        .stream()
                        .map(protocol -> summary.getOrDefault(protocol, new ProtocolAccumulator()).toResponse(protocol))
                        .toList()
        );
    }

    private ValidRequest validate(AdminProtocolComparisonRunRequestDto request) {
        AdminProtocolComparisonRunRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        String displayName = requiredText(validRequest.displayName(), "displayName", MAX_DISPLAY_NAME_LENGTH);
        String description = optionalText(validRequest.description(), "description", MAX_DESCRIPTION_LENGTH);
        String workloadId = requiredText(validRequest.workloadId(), "workloadId", MAX_DISPLAY_NAME_LENGTH);
        UUID targetExecutionGroupId = Objects.requireNonNull(
                validRequest.targetExecutionGroupId(),
                "targetExecutionGroupId is required."
        );
        if (!groupRepository.existsById(targetExecutionGroupId)) {
            throw new NoSuchElementException("Execution group not found.");
        }
        if (workloadCatalogService.getWorkload(workloadId).isEmpty()) {
            throw new IllegalArgumentException("Unknown research workload.");
        }

        List<ResearchProtocol> protocols = requiredUniqueList(
                validRequest.protocols(),
                "protocols",
                MAX_PROTOCOLS
        );
        List<ResearchOperation> operations = requiredUniqueList(
                validRequest.operations(),
                "operations",
                SUPPORTED_OPERATIONS.size()
        );
        for (ResearchOperation operation : operations) {
            if (!SUPPORTED_OPERATIONS.contains(operation)) {
                throw new IllegalArgumentException("Operation " + operation + " is not supported by M31 runner.");
            }
        }

        int repetitions = Objects.requireNonNull(validRequest.repetitions(), "repetitions is required.");
        if (repetitions < MIN_REPETITIONS || repetitions > MAX_REPETITIONS) {
            throw new IllegalArgumentException("repetitions must be between 1 and 20.");
        }

        for (ResearchProtocol protocol : protocols) {
            requireInvoker(protocol);
            for (ResearchOperation operation : operations) {
                validateProtocolCombination(protocol, operation);
                validateWorkloadCombination(workloadId, protocol, operation);
            }
        }

        return new ValidRequest(
                displayName,
                description,
                workloadId,
                targetExecutionGroupId,
                protocols,
                operations,
                repetitions,
                normalizeTags(validRequest.tags()),
                optionalText(validRequest.notes(), "notes", MAX_NOTES_LENGTH)
        );
    }

    private void validateProtocolCombination(ResearchProtocol protocol, ResearchOperation operation) {
        AdminResearchProtocolValidationResponseDto result = protocolValidator.validate(
                new AdminResearchProtocolValidationRequestDto(
                        protocol,
                        operation,
                        dataTransferMode(protocol),
                        payloadFormat(protocol)
                )
        );
        if (!result.valid()) {
            throw new IllegalArgumentException(result.reasonMessage());
        }
    }

    private void validateWorkloadCombination(String workloadId, ResearchProtocol protocol, ResearchOperation operation) {
        AdminResearchWorkloadValidationResponseDto result = workloadValidator.validate(
                new AdminResearchWorkloadValidationRequestDto(
                        workloadId,
                        protocol,
                        operation,
                        dataTransferMode(protocol),
                        payloadFormat(protocol)
                )
        );
        if (!result.valid()) {
            throw new IllegalArgumentException(result.reasonMessage());
        }
    }

    private ResearchProtocolOperationInvoker requireInvoker(ResearchProtocol protocol) {
        ResearchProtocolOperationInvoker invoker = invokers.get(protocol);
        if (invoker == null) {
            throw new IllegalArgumentException("Protocol " + protocol + " is not supported by M31 runner.");
        }
        return invoker;
    }

    private static ResearchDataTransferMode dataTransferMode(ResearchProtocol protocol) {
        return switch (protocol) {
            case REST, WEBSOCKET -> ResearchDataTransferMode.INLINE_JSON;
            case SOAP -> ResearchDataTransferMode.INLINE_XML;
        };
    }

    private static ResearchPayloadFormat payloadFormat(ResearchProtocol protocol) {
        return switch (protocol) {
            case REST, WEBSOCKET -> ResearchPayloadFormat.JSON;
            case SOAP -> ResearchPayloadFormat.XML;
        };
    }

    private static boolean allCompleted(Map<ResearchProtocol, ProtocolAccumulator> summary) {
        return summary.values()
                .stream()
                .allMatch(accumulator -> accumulator.failedScenarios == 0 && accumulator.skippedScenarios == 0);
    }

    private static String scenarioDisplayName(ResearchProtocol protocol, ResearchOperation operation, int repetition) {
        return protocol + " " + operation + " #" + repetition;
    }

    private static String rootFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.toString();
    }

    private static String requiredText(String value, String fieldName, int maxLength) {
        String trimmed = optionalText(value, fieldName, maxLength);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return trimmed;
    }

    private static String optionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not be longer than " + maxLength + " characters.");
        }
        return trimmed;
    }

    private static <T> List<T> requiredUniqueList(List<T> values, String fieldName, int maxSize) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(fieldName + " must contain at most " + maxSize + " items.");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " must not contain null values.");
        }

        Set<T> uniqueValues = new LinkedHashSet<>(values);
        if (uniqueValues.size() != values.size()) {
            throw new IllegalArgumentException(fieldName + " must not contain duplicate values.");
        }
        return List.copyOf(uniqueValues);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("tags must contain at most 20 items.");
        }

        return tags.stream()
                .map(tag -> requiredText(tag, "tag", MAX_TAG_LENGTH))
                .toList();
    }

    private record ValidRequest(
            String displayName,
            String description,
            String workloadId,
            UUID targetExecutionGroupId,
            List<ResearchProtocol> protocols,
            List<ResearchOperation> operations,
            int repetitions,
            List<String> tags,
            String notes
    ) {
    }

    private static final class ProtocolAccumulator {
        private long completedScenarios;
        private long failedScenarios;
        private long skippedScenarios;
        private long requestLatencyMsTotal;
        private long payloadResponseBytesTotal;
        private long errorCount;

        private void completed(long requestLatencyMs, long payloadResponseBytes) {
            completedScenarios++;
            requestLatencyMsTotal += requestLatencyMs;
            payloadResponseBytesTotal += payloadResponseBytes;
        }

        private void failed() {
            failedScenarios++;
            errorCount++;
        }

        private AdminProtocolComparisonRunResponseDto.ProtocolSummaryDto toResponse(ResearchProtocol protocol) {
            return new AdminProtocolComparisonRunResponseDto.ProtocolSummaryDto(
                    protocol,
                    completedScenarios,
                    failedScenarios,
                    skippedScenarios,
                    average(requestLatencyMsTotal, completedScenarios),
                    average(payloadResponseBytesTotal, completedScenarios),
                    errorCount
            );
        }

        private static BigDecimal average(long total, long count) {
            if (count == 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(total)
                    .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
    }
}
