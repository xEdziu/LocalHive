package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
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
    private final WorkerSelectionService workerSelectionService;
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
        ExecutionAssignmentMode assignmentMode = resolveAssignmentMode(validRequest.assignmentMode());
        UUID requestedWorkerId = resolveRequestedWorkerId(validRequest.workerId(), assignmentMode);

        WorkDefinitionVersion definitionVersion = findDefinitionVersion(definitionVersionId);
        requireExecutableDefinitionVersion(definitionVersion);

        ExecutionConfiguration executionConfiguration = resolveConfiguration(definitionVersion, validRequest);
        Worker worker = selectWorker(assignmentMode, requestedWorkerId, executionConfiguration.requestedResources());
        var execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                definitionVersion.getId(),
                executionConfiguration.configurationOverrides(),
                executionConfiguration.resourceOverrides(),
                validRequest.displayName()
        ));
        ExecutionAssignment assignment = assignExecution(execution.getId(), worker.getId(), assignmentMode);
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
            throw new IllegalArgumentException("Admin execution creation supports TASK work definitions only.");
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

    private Worker selectWorker(ExecutionAssignmentMode assignmentMode,
                                UUID requestedWorkerId,
                                ResourceRequest requestedResources) {
        return switch (assignmentMode) {
            case REQUIRE -> {
                Worker worker = findWorker(requestedWorkerId);
                requireApprovedWorker(worker);
                yield worker;
            }
            case AUTO -> workerSelectionService.selectAuto(requestedResources);
            case PREFER -> workerSelectionService.selectPreferred(requestedWorkerId, requestedResources);
        };
    }

    private ExecutionAssignment assignExecution(UUID executionId,
                                                UUID workerId,
                                                ExecutionAssignmentMode assignmentMode) {
        if (assignmentMode == ExecutionAssignmentMode.REQUIRE) {
            return assignmentService.assignExecutionToApprovedWorker(
                    executionId,
                    workerId,
                    assignmentMode,
                    LocalDateTime.now()
            );
        }

        return assignmentService.assignExecution(
                executionId,
                workerId,
                assignmentMode,
                LocalDateTime.now()
        );
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
                    ),
                    ResourceRequest.of(
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
            return new ExecutionConfiguration(sanitized, ResourceRequestOverrides.empty(), ResourceRequest.zero());
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

        return new ExecutionConfiguration(sanitized, ResourceRequestOverrides.empty(), ResourceRequest.zero());
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

    private static UUID resolveRequestedWorkerId(UUID workerId, ExecutionAssignmentMode assignmentMode) {
        return switch (assignmentMode) {
            case REQUIRE -> requireUuid(workerId, "workerId");
            case AUTO -> {
                if (workerId != null) {
                    throw new IllegalArgumentException("workerId must be absent for AUTO assignmentMode.");
                }
                yield null;
            }
            case PREFER -> requireUuid(workerId, "workerId");
        };
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

        return assignmentMode;
    }

    private record ExecutionConfiguration(
            JsonNode configurationOverrides,
            ResourceRequestOverrides resourceOverrides,
            ResourceRequest requestedResources
    ) {
    }
}
