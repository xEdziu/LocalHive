package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecutionDisplayName;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionGroupRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdminExecutionGroupCreationService {

    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^{}]+)}}");

    private final ObjectMapper objectMapper;
    private final WorkDefinitionVersionRepository versionRepository;
    private final ExecutionGroupRepository groupRepository;
    private final WorkExecutionRepository executionRepository;
    private final WorkExecutionCreationService executionCreationService;
    private final DockerWorkloadConfigurationValidator dockerConfigurationValidator;
    private final ExecutionGroupSchedulingService schedulingService;
    private final AdminExecutionGroupQueryService queryService;

    @Transactional
    public AdminExecutionGroupDetailResponseDto createExecutionGroup(AdminCreateExecutionGroupRequestDto request) {
        CreationPlan plan = preparePlan(request);
        LocalDateTime now = LocalDateTime.now();
        ExecutionGroup group = groupRepository.save(ExecutionGroup.create(
                plan.displayName(),
                ExecutionGroupMergeMode.NONE,
                plan.failurePolicy(),
                plan.shardCount(),
                now
        ));

        for (int shardIndex = 0; shardIndex < plan.shardCount(); shardIndex++) {
            ShardConfiguration shardConfiguration = shardConfiguration(
                    plan.configurationTemplate(),
                    shardIndex,
                    plan.shardCount()
            );
            WorkExecution execution = executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                    plan.definitionVersion().getId(),
                    shardConfiguration.configuration(),
                    ResourceRequestOverrides.of(
                            shardConfiguration.validated().memoryMb(),
                            shardConfiguration.validated().cpuCores(),
                            false
                    ),
                    childDisplayName(plan.displayName(), shardIndex, plan.shardCount())
            ));
            execution.attachToGroupAsShard(group, shardIndex, plan.shardCount());
            executionRepository.save(execution);
        }

        schedulingService.scheduleInitialPass(
                group.getId(),
                plan.assignmentMode(),
                plan.workerId(),
                now
        );
        return queryService.getGroup(group.getId())
                .orElseThrow(() -> new IllegalStateException("Created execution group cannot be read."));
    }

    private CreationPlan preparePlan(AdminCreateExecutionGroupRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null.");
        }

        int shardCount = requirePositiveShardCount(request.shardCount());
        ExecutionGroupMergeMode mergeMode = resolveMergeMode(request.mergeMode());
        if (mergeMode != ExecutionGroupMergeMode.NONE) {
            throw new IllegalArgumentException("Merge modes MASTER and AGENT are designed but not implemented in M18.");
        }

        ExecutionGroupFailurePolicy failurePolicy = resolveFailurePolicy(request.failurePolicy());
        ExecutionAssignmentMode assignmentMode = resolveAssignmentMode(request.assignmentMode());
        UUID workerId = resolveWorkerId(request.workerId(), assignmentMode);
        WorkDefinitionVersion definitionVersion = findDefinitionVersion(request.workDefinitionVersionId());
        requireShardedExecutableDefinitionVersion(definitionVersion);
        ObjectNode configurationTemplate = requireConfigurationTemplate(request.configurationTemplate());

        // Validate the template before creating the group.
        shardConfiguration(configurationTemplate, 0, shardCount);

        return new CreationPlan(
                request.displayName(),
                definitionVersion,
                shardCount,
                failurePolicy,
                assignmentMode,
                workerId,
                configurationTemplate
        );
    }

    private WorkDefinitionVersion findDefinitionVersion(UUID definitionVersionId) {
        if (definitionVersionId == null) {
            throw new IllegalArgumentException("workDefinitionVersionId is required.");
        }

        return versionRepository.findById(definitionVersionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Work definition version not found: " + definitionVersionId
                ));
    }

    private static void requireShardedExecutableDefinitionVersion(WorkDefinitionVersion definitionVersion) {
        if (definitionVersion.getApprovalStatus() != DefinitionApprovalStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "Work definition version must be APPROVED to create execution group. Current status: "
                            + definitionVersion.getApprovalStatus()
            );
        }
        if (definitionVersion.getDefinition().getWorkType() != WorkType.TASK) {
            throw new IllegalArgumentException("Execution group creation supports TASK work definitions only.");
        }
        if (!DOCKER_EXECUTOR_ID.equals(definitionVersion.getExecutorId())) {
            throw new IllegalArgumentException("Execution group creation supports localhive.docker.workload only.");
        }
    }

    private ObjectNode requireConfigurationTemplate(Map<String, Object> configurationTemplate) {
        if (configurationTemplate == null) {
            throw new IllegalArgumentException("configurationTemplate is required.");
        }

        JsonNode node = objectMapper.valueToTree(configurationTemplate);
        if (!node.isObject()) {
            throw new IllegalArgumentException("configurationTemplate must be a JSON object.");
        }
        return (ObjectNode) node;
    }

    private ShardConfiguration shardConfiguration(ObjectNode configurationTemplate, int shardIndex, int shardCount) {
        ObjectNode configuration = configurationTemplate.deepCopy();
        List<String> command = expandCommandTemplate(
                configuration.get("commandTemplate"),
                shardIndex,
                shardCount
        );
        configuration.remove("commandTemplate");
        configuration.remove("command");

        ArrayNode commandNode = configuration.putArray("command");
        command.forEach(commandNode::add);

        DockerWorkloadConfiguration.Validated validated =
                dockerConfigurationValidator.validateAdminConfiguration(configuration);
        return new ShardConfiguration(
                dockerConfigurationValidator.toConfiguration(validated),
                validated
        );
    }

    private static List<String> expandCommandTemplate(JsonNode commandTemplate, int shardIndex, int shardCount) {
        if (commandTemplate == null || commandTemplate.isNull()) {
            throw new IllegalArgumentException("configurationTemplate.commandTemplate is required.");
        }
        if (!commandTemplate.isArray() || commandTemplate.isEmpty()) {
            throw new IllegalArgumentException("configurationTemplate.commandTemplate must be a non-empty array.");
        }

        List<String> command = new ArrayList<>();
        commandTemplate.forEach(element -> {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("configurationTemplate.commandTemplate elements must be text values.");
            }
            if (element.textValue().isBlank()) {
                throw new IllegalArgumentException("configurationTemplate.commandTemplate elements must not be blank.");
            }
            command.add(expandPlaceholders(element.textValue(), shardIndex, shardCount));
        });
        return List.copyOf(command);
    }

    private static String expandPlaceholders(String value, int shardIndex, int shardCount) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "shardIndex" -> Integer.toString(shardIndex);
                case "shardCount" -> Integer.toString(shardCount);
                default -> throw new IllegalArgumentException(
                        "Unsupported commandTemplate placeholder: {{" + matcher.group(1) + "}}"
                );
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        String expanded = result.toString();
        if (expanded.contains("{{") || expanded.contains("}}")) {
            throw new IllegalArgumentException("Unsupported commandTemplate placeholder.");
        }
        return expanded;
    }

    private static int requirePositiveShardCount(Integer shardCount) {
        if (shardCount == null) {
            throw new IllegalArgumentException("shardCount is required.");
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be greater than 0.");
        }
        return shardCount;
    }

    private static ExecutionGroupMergeMode resolveMergeMode(String rawMergeMode) {
        if (rawMergeMode == null) {
            return ExecutionGroupMergeMode.NONE;
        }
        if (rawMergeMode.isBlank()) {
            throw new IllegalArgumentException("mergeMode must not be blank.");
        }

        try {
            return ExecutionGroupMergeMode.valueOf(rawMergeMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown mergeMode: " + rawMergeMode.trim());
        }
    }

    private static ExecutionGroupFailurePolicy resolveFailurePolicy(String rawFailurePolicy) {
        if (rawFailurePolicy == null) {
            return ExecutionGroupFailurePolicy.FAIL_FAST;
        }
        if (rawFailurePolicy.isBlank()) {
            throw new IllegalArgumentException("failurePolicy must not be blank.");
        }

        try {
            return ExecutionGroupFailurePolicy.valueOf(rawFailurePolicy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown failurePolicy: " + rawFailurePolicy.trim());
        }
    }

    private static ExecutionAssignmentMode resolveAssignmentMode(String rawAssignmentMode) {
        if (rawAssignmentMode == null) {
            return ExecutionAssignmentMode.AUTO;
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
        if (assignmentMode == ExecutionAssignmentMode.REQUIRE) {
            throw new IllegalArgumentException("REQUIRE assignmentMode is not supported for execution group creation in M18.");
        }

        return assignmentMode;
    }

    private static UUID resolveWorkerId(UUID workerId, ExecutionAssignmentMode assignmentMode) {
        return switch (assignmentMode) {
            case AUTO -> {
                if (workerId != null) {
                    throw new IllegalArgumentException("workerId must be absent for AUTO assignmentMode.");
                }
                yield null;
            }
            case PREFER -> {
                if (workerId == null) {
                    throw new IllegalArgumentException("workerId is required for PREFER assignmentMode.");
                }
                yield workerId;
            }
            case REQUIRE -> throw new IllegalArgumentException(
                    "REQUIRE assignmentMode is not supported for execution group creation in M18."
            );
        };
    }

    private static String childDisplayName(String displayName, int shardIndex, int shardCount) {
        String suffix = " shard " + (shardIndex + 1) + "/" + shardCount;
        String base = displayName == null ? "Execution group" : displayName.trim();
        if (base.isBlank()) {
            base = "Execution group";
        }
        if (base.length() + suffix.length() <= WorkExecutionDisplayName.MAX_LENGTH) {
            return base + suffix;
        }

        int maxBaseLength = WorkExecutionDisplayName.MAX_LENGTH - suffix.length();
        String truncated = base.substring(0, Math.max(1, maxBaseLength)).trim();
        return (truncated.isBlank() ? "Execution group" : truncated) + suffix;
    }

    private record CreationPlan(
            String displayName,
            WorkDefinitionVersion definitionVersion,
            int shardCount,
            ExecutionGroupFailurePolicy failurePolicy,
            ExecutionAssignmentMode assignmentMode,
            UUID workerId,
            ObjectNode configurationTemplate
    ) {
    }

    private record ShardConfiguration(
            ObjectNode configuration,
            DockerWorkloadConfiguration.Validated validated
    ) {
    }
}
