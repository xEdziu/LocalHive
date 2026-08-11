package dev.adrian.goral.localhivebackend.service.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEvent;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEventType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurement;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRun;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenario;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenarioStatus;
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
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationResponseDto;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkEventRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkMeasurementRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkRunRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkScenarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BenchmarkRunRecorderService {

    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 50;
    private static final int MAX_RUN_FAILURE_REASON_LENGTH = 1000;
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(token|api[_-]?key|password|secret|lease[_-]?token|lease[_-]?hash).*"
    );
    private static final Pattern AUTHORIZATION_BEARER = Pattern.compile(
            "(?i)Authorization\\s*:\\s*Bearer\\s+\\S+"
    );
    private static final Pattern API_KEY_HEADER = Pattern.compile(
            "(?i)X-API-KEY\\s*[:=]\\s*\\S+"
    );
    private static final Pattern EXECUTION_LEASE_HEADER = Pattern.compile(
            "(?i)X-EXECUTION-LEASE\\s*[:=]\\s*\\S+"
    );
    private static final Pattern API_KEY_VALUE = Pattern.compile(
            "(?i)apiKey\\s*[:=]\\s*\\S+"
    );
    private static final Pattern LEASE_TOKEN_VALUE = Pattern.compile(
            "(?i)leaseToken\\s*[:=]\\s*\\S+"
    );
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "\\b[A-Za-z]:[\\\\/][^\\s\"']+"
    );
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9:])/(?:[^\\s\"']+/)*[^\\s\"']+"
    );

    private final BenchmarkRunRepository runRepository;
    private final BenchmarkScenarioRepository scenarioRepository;
    private final BenchmarkMeasurementRepository measurementRepository;
    private final BenchmarkEventRepository eventRepository;
    private final BenchmarkRunQueryService queryService;
    private final ResearchWorkloadCatalogValidator workloadValidator;
    private final ObjectMapper objectMapper;

    @Transactional
    public AdminBenchmarkRunResponseDto createRun(AdminBenchmarkRunCreateRequestDto request, String createdBy) {
        AdminBenchmarkRunCreateRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        LocalDateTime now = LocalDateTime.now();
        BenchmarkRun run = runRepository.save(BenchmarkRun.create(
                validRequest.displayName(),
                validRequest.description(),
                createdBy,
                tagsToJson(validRequest.tags()),
                validRequest.notes(),
                now
        ));
        recordEvent(run, null, BenchmarkEventType.RUN_CREATED, "Benchmark run created.", null, null, null, now);
        return queryService.toRunResponse(run);
    }

    @Transactional
    public AdminBenchmarkRunResponseDto startRun(UUID benchmarkRunId) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        boolean alreadyRunning = run.getStatus() == BenchmarkRunStatus.RUNNING;
        LocalDateTime now = LocalDateTime.now();
        run.start(now);
        if (!alreadyRunning) {
            recordEvent(run, null, BenchmarkEventType.RUN_STARTED, "Benchmark run started.", null, null, null, now);
        }
        return queryService.toRunResponse(run);
    }

    @Transactional
    public AdminBenchmarkRunResponseDto completeRun(UUID benchmarkRunId) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        LocalDateTime now = LocalDateTime.now();
        run.complete(now);
        recordEvent(run, null, BenchmarkEventType.RUN_COMPLETED, "Benchmark run completed.", null, null, null, now);
        return queryService.toRunResponse(run);
    }

    @Transactional
    public AdminBenchmarkRunResponseDto failRun(UUID benchmarkRunId, AdminBenchmarkRunFailRequestDto request) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        LocalDateTime now = LocalDateTime.now();
        run.fail(now);
        String reason = request == null ? null : optionalSafeText(
                request.reason(),
                "reason",
                MAX_RUN_FAILURE_REASON_LENGTH
        );
        String message = reason == null
                ? "Benchmark run failed."
                : "Benchmark run failed: " + reason;
        recordEvent(run, null, BenchmarkEventType.RUN_FAILED, message, null, null, null, now);
        return queryService.toRunResponse(run);
    }

    @Transactional
    public AdminBenchmarkScenarioResponseDto addScenario(UUID benchmarkRunId, AdminBenchmarkScenarioCreateRequestDto request) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        if (run.isTerminal()) {
            throw new IllegalStateException("Cannot add benchmark scenario to terminal run " + run.getStatus() + ".");
        }

        AdminBenchmarkScenarioCreateRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        validateWorkloadCombination(validRequest);

        int scenarioIndex = scenarioRepository.findTopByBenchmarkRun_IdOrderByScenarioIndexDesc(run.getId())
                .map(previous -> previous.getScenarioIndex() + 1)
                .orElse(0);
        LocalDateTime now = LocalDateTime.now();
        BenchmarkScenario scenario = scenarioRepository.save(BenchmarkScenario.create(
                run,
                scenarioIndex,
                validRequest.displayName(),
                validRequest.workloadId(),
                validRequest.protocol(),
                validRequest.operation(),
                validRequest.dataTransferMode(),
                validRequest.payloadFormat(),
                validRequest.notes(),
                now
        ));
        recordEvent(run, scenario, BenchmarkEventType.SCENARIO_CREATED, "Benchmark scenario created.", null, null, null, now);
        return BenchmarkRunQueryService.toScenarioResponse(scenario);
    }

    @Transactional
    public AdminBenchmarkScenarioResponseDto startScenario(UUID benchmarkRunId, UUID scenarioId) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        BenchmarkScenario scenario = requireScenarioInRun(run, scenarioId);
        boolean alreadyRunning = scenario.getStatus() == BenchmarkScenarioStatus.RUNNING;
        LocalDateTime now = LocalDateTime.now();
        scenario.start(now);
        if (!alreadyRunning) {
            recordEvent(run, scenario, BenchmarkEventType.SCENARIO_STARTED, "Benchmark scenario started.", null, null, null, now);
        }
        return BenchmarkRunQueryService.toScenarioResponse(scenario);
    }

    @Transactional
    public AdminBenchmarkScenarioResponseDto completeScenario(UUID benchmarkRunId, UUID scenarioId) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        BenchmarkScenario scenario = requireScenarioInRun(run, scenarioId);
        LocalDateTime now = LocalDateTime.now();
        scenario.complete(now);
        recordEvent(run, scenario, BenchmarkEventType.SCENARIO_COMPLETED, "Benchmark scenario completed.", null, null, null, now);
        return BenchmarkRunQueryService.toScenarioResponse(scenario);
    }

    @Transactional
    public AdminBenchmarkScenarioResponseDto failScenario(
            UUID benchmarkRunId,
            UUID scenarioId,
            AdminBenchmarkScenarioFailRequestDto request
    ) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        BenchmarkScenario scenario = requireScenarioInRun(run, scenarioId);
        LocalDateTime now = LocalDateTime.now();
        String errorCode = request == null ? null : optionalSafeText(
                request.errorCode(),
                "errorCode",
                BenchmarkScenario.MAX_ERROR_CODE_LENGTH
        );
        String errorMessage = request == null ? null : optionalSafeText(
                request.errorMessage(),
                "errorMessage",
                BenchmarkScenario.MAX_ERROR_MESSAGE_LENGTH
        );
        scenario.fail(errorCode, errorMessage, now);
        recordEvent(run, scenario, BenchmarkEventType.SCENARIO_FAILED, "Benchmark scenario failed.", null, null, null, now);
        return BenchmarkRunQueryService.toScenarioResponse(scenario);
    }

    @Transactional
    public AdminBenchmarkMeasurementResponseDto recordMeasurement(
            UUID benchmarkRunId,
            AdminBenchmarkMeasurementCreateRequestDto request
    ) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        AdminBenchmarkMeasurementCreateRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        BenchmarkScenario scenario = validRequest.scenarioId() == null
                ? null
                : requireScenarioInRun(run, validRequest.scenarioId());

        LocalDateTime now = LocalDateTime.now();
        BenchmarkMeasurement measurement = measurementRepository.save(BenchmarkMeasurement.create(
                run,
                scenario,
                validRequest.type(),
                validRequest.valueNumeric(),
                validRequest.unit(),
                validRequest.notes(),
                now
        ));
        recordEvent(run, scenario, BenchmarkEventType.MEASUREMENT_RECORDED, "Benchmark measurement recorded.", null, null, null, now);
        return BenchmarkRunQueryService.toMeasurementResponse(measurement);
    }

    @Transactional
    public AdminBenchmarkEventResponseDto recordEvent(UUID benchmarkRunId, AdminBenchmarkEventCreateRequestDto request) {
        BenchmarkRun run = requireRun(benchmarkRunId);
        AdminBenchmarkEventCreateRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        BenchmarkScenario scenario = validRequest.scenarioId() == null
                ? null
                : requireScenarioInRun(run, validRequest.scenarioId());
        String metadataJson = validateMetadataJson(validRequest.metadataJson());

        BenchmarkEvent event = eventRepository.save(BenchmarkEvent.create(
                run,
                scenario,
                validRequest.type(),
                validRequest.message(),
                metadataJson,
                validRequest.relatedExecutionId(),
                validRequest.relatedExecutionGroupId(),
                LocalDateTime.now()
        ));
        return BenchmarkRunQueryService.toEventResponse(event);
    }

    private BenchmarkRun requireRun(UUID benchmarkRunId) {
        UUID validBenchmarkRunId = Objects.requireNonNull(
                benchmarkRunId,
                "benchmarkRunId must not be null."
        );
        return runRepository.findById(validBenchmarkRunId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark run not found."));
    }

    private BenchmarkScenario requireScenarioInRun(BenchmarkRun run, UUID scenarioId) {
        UUID validScenarioId = Objects.requireNonNull(scenarioId, "scenarioId must not be null.");
        BenchmarkScenario scenario = scenarioRepository.findById(validScenarioId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark scenario not found."));
        if (!scenario.belongsTo(run)) {
            throw new IllegalArgumentException("scenarioId must belong to the benchmark run.");
        }

        return scenario;
    }

    private void validateWorkloadCombination(AdminBenchmarkScenarioCreateRequestDto request) {
        AdminResearchWorkloadValidationResponseDto result = workloadValidator.validate(
                new AdminResearchWorkloadValidationRequestDto(
                        request.workloadId(),
                        request.protocol(),
                        request.operation(),
                        request.dataTransferMode(),
                        request.payloadFormat()
                )
        );
        if (!result.valid()) {
            throw new IllegalArgumentException(result.reasonMessage());
        }
    }

    private void recordEvent(BenchmarkRun run,
                             BenchmarkScenario scenario,
                             BenchmarkEventType type,
                             String message,
                             String metadataJson,
                             UUID relatedExecutionId,
                             UUID relatedExecutionGroupId,
                             LocalDateTime occurredAt) {
        eventRepository.save(BenchmarkEvent.create(
                run,
                scenario,
                type,
                message,
                metadataJson,
                relatedExecutionId,
                relatedExecutionGroupId,
                occurredAt
        ));
    }

    private JsonNode tagsToJson(List<String> tags) {
        List<String> normalizedTags = normalizeTags(tags);
        if (normalizedTags.isEmpty()) {
            return null;
        }

        ArrayNode arrayNode = objectMapper.createArrayNode();
        normalizedTags.forEach(arrayNode::add);
        return arrayNode;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("tags must contain at most 20 items.");
        }

        return tags.stream()
                .map(tag -> {
                    if (tag == null || tag.isBlank()) {
                        throw new IllegalArgumentException("tags must not contain blank values.");
                    }
                    String trimmed = tag.trim();
                    if (trimmed.length() > MAX_TAG_LENGTH) {
                        throw new IllegalArgumentException("tag must not be longer than 50 characters.");
                    }
                    return trimmed;
                })
                .toList();
    }

    private String validateMetadataJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        String trimmed = metadataJson.trim();
        if (trimmed.length() > BenchmarkEvent.MAX_METADATA_JSON_LENGTH) {
            throw new IllegalArgumentException("metadataJson must not be longer than 8000 characters.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadataJson must be valid JSON.", e);
        }

        requireSafeMetadata(root);
        return trimmed;
    }

    private static void requireSafeMetadata(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (isSensitiveName(fieldName)) {
                    throw new IllegalArgumentException("metadataJson must not contain secret-like keys.");
                }
                requireSafeMetadata(node.get(fieldName));
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                requireSafeMetadata(element);
            }
            return;
        }
        if (node.isTextual() && containsAbsolutePath(node.asText())) {
            throw new IllegalArgumentException("metadataJson must not contain absolute filesystem paths.");
        }
    }

    private static String optionalSafeText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not be longer than " + maxLength + " characters.");
        }

        String redacted = AUTHORIZATION_BEARER.matcher(trimmed)
                .replaceAll("Authorization: Bearer <redacted>");
        redacted = API_KEY_HEADER.matcher(redacted)
                .replaceAll("X-API-KEY=<redacted>");
        redacted = EXECUTION_LEASE_HEADER.matcher(redacted)
                .replaceAll("X-EXECUTION-LEASE=<redacted>");
        redacted = API_KEY_VALUE.matcher(redacted)
                .replaceAll("apiKey=<redacted>");
        redacted = LEASE_TOKEN_VALUE.matcher(redacted)
                .replaceAll("leaseToken=<redacted>");
        redacted = WINDOWS_ABSOLUTE_PATH.matcher(redacted)
                .replaceAll("<redacted-path>");
        redacted = UNIX_ABSOLUTE_PATH.matcher(redacted)
                .replaceAll("<redacted-path>");

        return redacted;
    }

    private static boolean isSensitiveName(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_NAME.matcher(normalized).matches();
    }

    private static boolean containsAbsolutePath(String value) {
        return WINDOWS_ABSOLUTE_PATH.matcher(value).find()
                || UNIX_ABSOLUTE_PATH.matcher(value).find();
    }
}
