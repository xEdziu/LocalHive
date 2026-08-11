package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultExpectedSystemBehavior;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultInjectionMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultScenarioType;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultSeverity;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExecutionShape;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadType;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioCatalogResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioDescriptorResponseDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResearchFaultScenarioCatalogService {

    private static final List<ResearchFaultScenarioDescriptor> SCENARIOS = List.of(
            new ResearchFaultScenarioDescriptor(
                    "WORKER_OFFLINE_DURING_EXECUTION",
                    "Worker offline during execution",
                    ResearchFaultScenarioType.WORKER_OFFLINE,
                    ResearchFaultSeverity.HIGH,
                    ResearchFaultInjectionMode.ENVIRONMENT_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_TIMEOUT,
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.RECONCILE_GROUP
                    ),
                    List.of(
                            ResearchWorkloadType.SHARDED_OPTIMIZATION,
                            ResearchWorkloadType.AGENT_MERGE,
                            ResearchWorkloadType.MANY_SMALL_JOBS,
                            ResearchWorkloadType.FEW_HEAVY_JOBS
                    ),
                    List.of(
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE
                    ),
                    "Manual loss of a running worker while an execution or group is being observed.",
                    "Checks timeout, offline reconciliation, and safe status reporting.",
                    true,
                    true,
                    false,
                    true,
                    List.of("offline", "timeout", "manual")
            ),
            new ResearchFaultScenarioDescriptor(
                    "TASK_FAILURE_EXIT_CODE",
                    "Task failure exit code",
                    ResearchFaultScenarioType.TASK_FAILURE,
                    ResearchFaultSeverity.MEDIUM,
                    ResearchFaultInjectionMode.WORKLOAD_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_FAILURE_STATUS,
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.GET_EXECUTION_STATUS),
                    List.of(ResearchWorkloadType.FAILING_TASK),
                    List.of(ResearchWorkloadExecutionShape.SINGLE_EXECUTION),
                    "Controlled failing single execution with safe failure metadata.",
                    "Checks failed status propagation and safe error reporting.",
                    false,
                    true,
                    true,
                    false,
                    List.of("failure", "single", "exit-code")
            ),
            new ResearchFaultScenarioDescriptor(
                    "MERGE_FAILURE_AGENT",
                    "Agent merge failure",
                    ResearchFaultScenarioType.MERGE_FAILURE,
                    ResearchFaultSeverity.HIGH,
                    ResearchFaultInjectionMode.WORKLOAD_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_FAILURE_STATUS,
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.GET_GROUP_ARTIFACTS
                    ),
                    List.of(ResearchWorkloadType.AGENT_MERGE),
                    List.of(ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE),
                    "Agent-side merge execution fails after shard completion.",
                    "Checks merge failure status derivation and safe group detail reporting.",
                    true,
                    true,
                    true,
                    false,
                    List.of("merge", "failure", "group")
            ),
            new ResearchFaultScenarioDescriptor(
                    "CANCELLED_GROUP_QUEUED",
                    "Queued group cancellation",
                    ResearchFaultScenarioType.GROUP_CANCELLED,
                    ResearchFaultSeverity.MEDIUM,
                    ResearchFaultInjectionMode.REQUEST_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_CANCELLATION,
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.CANCEL_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY
                    ),
                    List.of(ResearchWorkloadType.CANCELLED_GROUP),
                    List.of(ResearchWorkloadExecutionShape.EXECUTION_GROUP),
                    "Cancellation request while some group children are still queued.",
                    "Checks cancellation status, child status derivation, and safe observability.",
                    true,
                    false,
                    true,
                    false,
                    List.of("cancel", "queued", "group")
            ),
            new ResearchFaultScenarioDescriptor(
                    "INVALID_REST_PAYLOAD",
                    "Invalid REST payload",
                    ResearchFaultScenarioType.INVALID_PAYLOAD,
                    ResearchFaultSeverity.LOW,
                    ResearchFaultInjectionMode.REQUEST_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_REJECTION,
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.CREATE_EXECUTION_GROUP),
                    List.of(
                            ResearchWorkloadType.NO_OP,
                            ResearchWorkloadType.SMALL_JSON,
                            ResearchWorkloadType.FILE_INPUT_OUTPUT,
                            ResearchWorkloadType.SHARDED_OPTIMIZATION,
                            ResearchWorkloadType.AGENT_MERGE,
                            ResearchWorkloadType.LONG_RUNNING,
                            ResearchWorkloadType.FAILING_TASK,
                            ResearchWorkloadType.CANCELLED_GROUP,
                            ResearchWorkloadType.MANY_SMALL_JOBS,
                            ResearchWorkloadType.FEW_HEAVY_JOBS
                    ),
                    List.of(
                            ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE
                    ),
                    "Invalid REST request body rejected before runtime state mutation.",
                    "Checks safe validation errors on the REST baseline.",
                    false,
                    false,
                    false,
                    false,
                    List.of("rest", "invalid-payload", "safe-rejection")
            ),
            new ResearchFaultScenarioDescriptor(
                    "MALFORMED_SOAP_REQUEST",
                    "Malformed SOAP request",
                    ResearchFaultScenarioType.MALFORMED_SOAP,
                    ResearchFaultSeverity.LOW,
                    ResearchFaultInjectionMode.REQUEST_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_ERROR,
                    List.of(ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.GET_GROUP_ARTIFACTS,
                            ResearchOperation.CANCEL_GROUP,
                            ResearchOperation.RECONCILE_GROUP
                    ),
                    List.of(ResearchWorkloadType.SHARDED_OPTIMIZATION, ResearchWorkloadType.AGENT_MERGE),
                    List.of(
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE
                    ),
                    "Malformed SOAP/XML request rejected with a safe SOAP error.",
                    "Checks XML adapter error handling without runtime mutation.",
                    true,
                    false,
                    false,
                    false,
                    List.of("soap", "malformed", "safe-error")
            ),
            new ResearchFaultScenarioDescriptor(
                    "BROKEN_WEBSOCKET_STREAM",
                    "Broken WebSocket stream",
                    ResearchFaultScenarioType.BROKEN_WEBSOCKET_STREAM,
                    ResearchFaultSeverity.MEDIUM,
                    ResearchFaultInjectionMode.STREAM_LEVEL,
                    ResearchFaultExpectedSystemBehavior.CLEAN_DISCONNECT,
                    List.of(ResearchProtocol.WEBSOCKET),
                    List.of(ResearchOperation.STREAM_GROUP_ACTIVITY),
                    List.of(ResearchWorkloadType.MANY_SMALL_JOBS, ResearchWorkloadType.FEW_HEAVY_JOBS),
                    List.of(ResearchWorkloadExecutionShape.EXECUTION_GROUP),
                    "Interrupted group activity stream closes cleanly.",
                    "Checks stream cleanup and safe reconnect behavior.",
                    true,
                    false,
                    false,
                    false,
                    List.of("websocket", "stream", "disconnect")
            ),
            new ResearchFaultScenarioDescriptor(
                    "UNSUPPORTED_PROTOCOL_COMBINATION",
                    "Unsupported protocol combination",
                    ResearchFaultScenarioType.UNSUPPORTED_PROTOCOL_COMBINATION,
                    ResearchFaultSeverity.LOW,
                    ResearchFaultInjectionMode.REQUEST_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_REJECTION,
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_SINGLE_EXECUTION,
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.DOWNLOAD_ARTIFACT,
                            ResearchOperation.STREAM_GROUP_ACTIVITY
                    ),
                    List.of(
                            ResearchWorkloadType.NO_OP,
                            ResearchWorkloadType.SHARDED_OPTIMIZATION,
                            ResearchWorkloadType.AGENT_MERGE,
                            ResearchWorkloadType.MANY_SMALL_JOBS,
                            ResearchWorkloadType.FEW_HEAVY_JOBS
                    ),
                    List.of(
                            ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                            ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE
                    ),
                    "Intentionally unsupported protocol tuple used to verify safe rejection paths.",
                    "Checks that unsupported research combinations fail without side effects.",
                    false,
                    false,
                    false,
                    false,
                    List.of("protocol", "negative-case", "safe-rejection")
            ),
            new ResearchFaultScenarioDescriptor(
                    "LONG_RUNNING_TIMEOUT",
                    "Long-running timeout",
                    ResearchFaultScenarioType.TIMEOUT,
                    ResearchFaultSeverity.MEDIUM,
                    ResearchFaultInjectionMode.WORKLOAD_LEVEL,
                    ResearchFaultExpectedSystemBehavior.SAFE_TIMEOUT,
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.GET_EXECUTION_STATUS),
                    List.of(ResearchWorkloadType.LONG_RUNNING),
                    List.of(ResearchWorkloadExecutionShape.SINGLE_EXECUTION),
                    "Long-running single execution exceeds the configured test timeout.",
                    "Checks timeout reporting and terminal status safety.",
                    false,
                    true,
                    true,
                    false,
                    List.of("timeout", "long-running", "single")
            )
    );

    private static final Map<String, ResearchFaultScenarioDescriptor> SCENARIOS_BY_ID = SCENARIOS.stream()
            .collect(Collectors.toUnmodifiableMap(ResearchFaultScenarioDescriptor::id, Function.identity()));

    public AdminResearchFaultScenarioCatalogResponseDto getCatalog() {
        return new AdminResearchFaultScenarioCatalogResponseDto(
                LocalDateTime.now(),
                SCENARIOS.stream()
                        .map(ResearchFaultScenarioCatalogService::toResponse)
                        .toList()
        );
    }

    public Optional<AdminResearchFaultScenarioDescriptorResponseDto> getScenario(String scenarioId) {
        return findDescriptor(scenarioId).map(ResearchFaultScenarioCatalogService::toResponse);
    }

    Optional<ResearchFaultScenarioDescriptor> findDescriptor(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SCENARIOS_BY_ID.get(scenarioId.trim()));
    }

    private static AdminResearchFaultScenarioDescriptorResponseDto toResponse(ResearchFaultScenarioDescriptor descriptor) {
        return new AdminResearchFaultScenarioDescriptorResponseDto(
                descriptor.id(),
                descriptor.name(),
                descriptor.type(),
                descriptor.severity(),
                descriptor.injectionMode(),
                descriptor.expectedSystemBehavior(),
                descriptor.recommendedProtocols(),
                descriptor.recommendedOperations(),
                descriptor.compatibleWorkloadTypes(),
                descriptor.compatibleExecutionShapes(),
                descriptor.description(),
                descriptor.researchPurpose(),
                descriptor.requiresExistingExecutionGroup(),
                descriptor.requiresRunningWorker(),
                descriptor.requiresDocker(),
                descriptor.requiresManualAction(),
                descriptor.tags()
        );
    }
}
