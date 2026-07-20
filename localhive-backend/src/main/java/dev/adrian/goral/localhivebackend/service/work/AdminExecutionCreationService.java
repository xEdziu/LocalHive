package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionResponseDto;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminExecutionCreationService {

    private static final String NO_OP_EXECUTOR_ID = "localhive.no-op";
    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";

    private final WorkDefinitionVersionRepository versionRepository;
    private final WorkerRepository workerRepository;
    private final WorkExecutionCreationService creationService;
    private final WorkExecutionAssignmentService assignmentService;
    private final DockerWorkloadConfigurationValidator dockerWorkloadConfigurationValidator;

    @Transactional
    public AdminCreateExecutionResponseDto createExecution(AdminCreateExecutionRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null.");
        }
        AdminCreateExecutionRequestDto validRequest = request;
        UUID definitionVersionId = requireUuid(
                validRequest.workDefinitionVersionId(),
                "workDefinitionVersionId"
        );
        UUID workerId = requireUuid(validRequest.workerId(), "workerId");
        ExecutionAssignmentMode assignmentMode = resolveAssignmentMode(validRequest.assignmentMode());

        WorkDefinitionVersion definitionVersion = findDefinitionVersion(definitionVersionId);
        requireExecutableDefinitionVersion(definitionVersion);

        Worker worker = findWorker(workerId);
        requireApprovedWorker(worker);

        ExecutionConfiguration executionConfiguration = resolveConfiguration(definitionVersion, validRequest);
        var execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                definitionVersion.getId(),
                executionConfiguration.configurationOverrides(),
                executionConfiguration.resourceOverrides(),
                validRequest.displayName()
        ));
        ExecutionAssignment assignment = assignmentService.assignExecutionToApprovedWorker(
                execution.getId(),
                worker.getId(),
                assignmentMode,
                LocalDateTime.now()
        );
        return AdminCreateExecutionResponseDto.from(assignment);
    }

    private WorkDefinitionVersion findDefinitionVersion(UUID definitionVersionId) {
        return versionRepository.findById(definitionVersionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Work definition version not found: " + definitionVersionId
                ));
    }

    private Worker findWorker(UUID workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new NoSuchElementException("Worker not found: " + workerId));
    }

    private static void requireExecutableDefinitionVersion(WorkDefinitionVersion definitionVersion) {
        if (definitionVersion.getApprovalStatus() != DefinitionApprovalStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "Work definition version must be APPROVED to create execution. Current status: "
                            + definitionVersion.getApprovalStatus()
            );
        }
        if (definitionVersion.getDefinition().getWorkType() != WorkType.TASK) {
            throw new IllegalArgumentException("M10 supports execution creation for TASK work definitions only.");
        }
        if (!NO_OP_EXECUTOR_ID.equals(definitionVersion.getExecutorId())
                && !DOCKER_EXECUTOR_ID.equals(definitionVersion.getExecutorId())) {
            throw new IllegalArgumentException("Unsupported executor for admin execution creation: "
                    + definitionVersion.getExecutorId());
        }
    }

    private static void requireApprovedWorker(Worker worker) {
        if (worker.getApprovalStatus() != WorkerApprovalStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "Worker must be APPROVED to receive admin execution assignment."
            );
        }
    }

    private ExecutionConfiguration resolveConfiguration(WorkDefinitionVersion definitionVersion,
                                                        AdminCreateExecutionRequestDto request) {
        if (NO_OP_EXECUTOR_ID.equals(definitionVersion.getExecutorId())) {
            return noOpConfiguration(request.configuration());
        }
        if (DOCKER_EXECUTOR_ID.equals(definitionVersion.getExecutorId())) {
            DockerWorkloadConfiguration.Validated dockerConfiguration =
                    dockerWorkloadConfigurationValidator.validateAdminConfiguration(requireConfigurationObject(
                            request.configuration(),
                            "configuration is required for localhive.docker.workload."
                    ));
            return new ExecutionConfiguration(
                    dockerWorkloadConfigurationValidator.toConfiguration(dockerConfiguration),
                    ResourceRequestOverrides.of(
                            dockerConfiguration.memoryMb(),
                            dockerConfiguration.cpuCores(),
                            false
                    )
            );
        }

        throw new IllegalArgumentException("Unsupported executor for admin execution creation: "
                + definitionVersion.getExecutorId());
    }

    private static ExecutionConfiguration noOpConfiguration(Map<String, Object> configuration) {
        ObjectNode sanitized = JsonNodeFactory.instance.objectNode();
        if (configuration == null) {
            return new ExecutionConfiguration(sanitized, ResourceRequestOverrides.empty());
        }

        for (String fieldName : configuration.keySet()) {
            if (!"message".equals(fieldName)) {
                throw new IllegalArgumentException(
                        "configuration may contain only message for localhive.no-op."
                );
            }
        }

        Object message = configuration.get("message");
        if (message != null) {
            if (!(message instanceof String textMessage)) {
                throw new IllegalArgumentException("configuration.message must be a text value.");
            }
            sanitized.put("message", textMessage);
        }

        return new ExecutionConfiguration(sanitized, ResourceRequestOverrides.empty());
    }

    private static ObjectNode requireConfigurationObject(Map<String, Object> configuration, String message) {
        if (configuration == null) {
            throw new IllegalArgumentException(message);
        }

        return toObjectNode(configuration);
    }

    private static ObjectNode toObjectNode(Map<String, Object> values) {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        values.forEach((key, value) -> object.set(key, toJsonNode(value)));
        return object;
    }

    private static JsonNode toJsonNode(Object value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (value instanceof String text) {
            return JsonNodeFactory.instance.textNode(text);
        }
        if (value instanceof Boolean bool) {
            return JsonNodeFactory.instance.booleanNode(bool);
        }
        if (value instanceof Integer number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Long number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Double number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Float number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("configuration object keys must be text values.");
                }
                object.set(key, toJsonNode(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            list.forEach(element -> array.add(toJsonNode(element)));
            return array;
        }

        throw new IllegalArgumentException("configuration contains unsupported value type.");
    }

    private static UUID requireUuid(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }

        return value;
    }

    private static ExecutionAssignmentMode resolveAssignmentMode(String rawAssignmentMode) {
        if (rawAssignmentMode == null) {
            return ExecutionAssignmentMode.REQUIRE;
        }
        if (rawAssignmentMode.isBlank()) {
            throw new IllegalArgumentException("assignmentMode must not be blank.");
        }

        ExecutionAssignmentMode assignmentMode;
        try {
            assignmentMode = ExecutionAssignmentMode.valueOf(rawAssignmentMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown assignmentMode: " + rawAssignmentMode.trim());
        }

        if (assignmentMode != ExecutionAssignmentMode.REQUIRE) {
            throw new IllegalArgumentException("M10 supports only REQUIRE assignmentMode.");
        }

        return assignmentMode;
    }

    private record ExecutionConfiguration(
            JsonNode configurationOverrides,
            ResourceRequestOverrides resourceOverrides
    ) {
    }
}
