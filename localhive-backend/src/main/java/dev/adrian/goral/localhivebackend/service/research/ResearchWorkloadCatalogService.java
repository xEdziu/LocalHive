package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadComplexity;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadDataProfile;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExecutionShape;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExpectedOutcome;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadType;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadCatalogResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadDescriptorResponseDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResearchWorkloadCatalogService {

    private static final List<ResearchWorkloadDescriptor> WORKLOADS = List.of(
            new ResearchWorkloadDescriptor(
                    "NO_OP_TINY",
                    "NO_OP tiny job",
                    ResearchWorkloadType.NO_OP,
                    ResearchWorkloadComplexity.TINY,
                    ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.GET_EXECUTION_STATUS),
                    "Minimal baseline workload used to estimate protocol overhead without meaningful execution cost.",
                    "Protocol overhead baseline.",
                    false,
                    false,
                    false,
                    null,
                    30,
                    List.of("baseline", "tiny", "overhead")
            ),
            new ResearchWorkloadDescriptor(
                    "SMALL_JSON_ECHO",
                    "Small JSON echo",
                    ResearchWorkloadType.SMALL_JSON,
                    ResearchWorkloadComplexity.SMALL,
                    ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.GET_EXECUTION_STATUS),
                    "Small inline payload workload for JSON request/response handling checks.",
                    "Inline JSON/XML payload handling baseline.",
                    false,
                    false,
                    false,
                    null,
                    30,
                    List.of("inline", "json", "small")
            ),
            new ResearchWorkloadDescriptor(
                    "FILE_IO_SMALL",
                    "Small file input/output job",
                    ResearchWorkloadType.FILE_INPUT_OUTPUT,
                    ResearchWorkloadComplexity.SMALL,
                    ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(
                            ResearchWorkloadDataProfile.WORKSPACE_ARTIFACT_REQUIRED,
                            ResearchWorkloadDataProfile.OUTPUT_ARTIFACTS_EXPECTED
                    ),
                    List.of(ResearchProtocol.REST),
                    List.of(
                            ResearchOperation.CREATE_SINGLE_EXECUTION,
                            ResearchOperation.GET_EXECUTION_STATUS,
                            ResearchOperation.DOWNLOAD_ARTIFACT
                    ),
                    "Single execution that consumes a small workspace package and uploads output artifacts.",
                    "Workspace download and output artifact upload behavior.",
                    true,
                    true,
                    false,
                    null,
                    60,
                    List.of("workspace", "artifact", "file-io")
            ),
            new ResearchWorkloadDescriptor(
                    "SHARDED_OPTIMIZATION_4",
                    "Sharded optimization, 4 shards",
                    ResearchWorkloadType.SHARDED_OPTIMIZATION,
                    ResearchWorkloadComplexity.MEDIUM,
                    ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(
                            ResearchWorkloadDataProfile.WORKSPACE_ARTIFACT_REQUIRED,
                            ResearchWorkloadDataProfile.SHARDED_OUTPUTS_EXPECTED
                    ),
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.GET_GROUP_ARTIFACTS
                    ),
                    "Four independent optimization shards without Agent merge.",
                    "Shard scheduling, group observability, and per-shard artifact comparison.",
                    true,
                    true,
                    false,
                    4,
                    120,
                    List.of("sharding", "optimization", "medium")
            ),
            new ResearchWorkloadDescriptor(
                    "AGENT_MERGE_OPTIMIZATION_4",
                    "Agent merge optimization, 4 shards",
                    ResearchWorkloadType.AGENT_MERGE,
                    ResearchWorkloadComplexity.MEDIUM,
                    ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(
                            ResearchWorkloadDataProfile.WORKSPACE_ARTIFACT_REQUIRED,
                            ResearchWorkloadDataProfile.SHARDED_OUTPUTS_EXPECTED,
                            ResearchWorkloadDataProfile.MERGE_OUTPUTS_EXPECTED
                    ),
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.GET_GROUP_ARTIFACTS
                    ),
                    "Four optimization shards followed by one Agent-side merge execution.",
                    "Agent merge orchestration and merged output artifact behavior.",
                    true,
                    true,
                    true,
                    4,
                    180,
                    List.of("sharding", "merge", "optimization")
            ),
            new ResearchWorkloadDescriptor(
                    "LONG_RUNNING_SINGLE",
                    "Long-running single job",
                    ResearchWorkloadType.LONG_RUNNING,
                    ResearchWorkloadComplexity.MEDIUM,
                    ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.GET_EXECUTION_STATUS),
                    "Single execution that stays active long enough for repeated status observations.",
                    "Live status and polling latency observation.",
                    true,
                    false,
                    false,
                    null,
                    300,
                    List.of("long-running", "status", "latency")
            ),
            new ResearchWorkloadDescriptor(
                    "FAILING_TASK_SINGLE",
                    "Failing single job",
                    ResearchWorkloadType.FAILING_TASK,
                    ResearchWorkloadComplexity.SMALL,
                    ResearchWorkloadExecutionShape.SINGLE_EXECUTION,
                    ResearchWorkloadExpectedOutcome.FAILED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST),
                    List.of(ResearchOperation.CREATE_SINGLE_EXECUTION, ResearchOperation.GET_EXECUTION_STATUS),
                    "Single execution that is expected to report a controlled failure.",
                    "Failure reporting and safe error metadata behavior.",
                    true,
                    false,
                    false,
                    null,
                    60,
                    List.of("failure", "error", "single")
            ),
            new ResearchWorkloadDescriptor(
                    "CANCELLED_GROUP_QUEUED",
                    "Queued group cancellation",
                    ResearchWorkloadType.CANCELLED_GROUP,
                    ResearchWorkloadComplexity.SMALL,
                    ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                    ResearchWorkloadExpectedOutcome.CANCELLED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.CANCEL_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY
                    ),
                    "Execution group intended to be cancelled before all queued children run.",
                    "Group cancellation and lifecycle action behavior.",
                    true,
                    false,
                    false,
                    4,
                    60,
                    List.of("cancel", "group", "lifecycle")
            ),
            new ResearchWorkloadDescriptor(
                    "MANY_SMALL_JOBS_20",
                    "Many small jobs, 20 shards",
                    ResearchWorkloadType.MANY_SMALL_JOBS,
                    ResearchWorkloadComplexity.MEDIUM,
                    ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.STREAM_GROUP_ACTIVITY
                    ),
                    "Group descriptor for twenty lightweight child executions.",
                    "Throughput, scheduling waves, and protocol round-trip comparison.",
                    true,
                    false,
                    false,
                    20,
                    120,
                    List.of("throughput", "many-small", "round-trips")
            ),
            new ResearchWorkloadDescriptor(
                    "FEW_HEAVY_JOBS_3",
                    "Few heavy jobs, 3 shards",
                    ResearchWorkloadType.FEW_HEAVY_JOBS,
                    ResearchWorkloadComplexity.LARGE,
                    ResearchWorkloadExecutionShape.EXECUTION_GROUP,
                    ResearchWorkloadExpectedOutcome.SUCCEEDED,
                    List.of(ResearchWorkloadDataProfile.INLINE_ONLY),
                    List.of(ResearchProtocol.REST, ResearchProtocol.WEBSOCKET, ResearchProtocol.SOAP),
                    List.of(
                            ResearchOperation.CREATE_EXECUTION_GROUP,
                            ResearchOperation.GET_GROUP_DETAIL,
                            ResearchOperation.GET_GROUP_ACTIVITY,
                            ResearchOperation.STREAM_GROUP_ACTIVITY
                    ),
                    "Group descriptor for a small number of execution-dominated heavy jobs.",
                    "Execution-dominated comparison where protocol overhead should be less visible.",
                    true,
                    false,
                    false,
                    3,
                    900,
                    List.of("heavy", "large", "execution-dominated")
            )
    );

    private static final Map<String, ResearchWorkloadDescriptor> WORKLOADS_BY_ID = WORKLOADS.stream()
            .collect(Collectors.toUnmodifiableMap(ResearchWorkloadDescriptor::id, Function.identity()));

    public AdminResearchWorkloadCatalogResponseDto getCatalog() {
        return new AdminResearchWorkloadCatalogResponseDto(
                LocalDateTime.now(),
                WORKLOADS.stream()
                        .map(ResearchWorkloadCatalogService::toResponse)
                        .toList()
        );
    }

    public Optional<AdminResearchWorkloadDescriptorResponseDto> getWorkload(String workloadId) {
        return findDescriptor(workloadId).map(ResearchWorkloadCatalogService::toResponse);
    }

    Optional<ResearchWorkloadDescriptor> findDescriptor(String workloadId) {
        if (workloadId == null || workloadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(WORKLOADS_BY_ID.get(workloadId.trim()));
    }

    private static AdminResearchWorkloadDescriptorResponseDto toResponse(ResearchWorkloadDescriptor descriptor) {
        return new AdminResearchWorkloadDescriptorResponseDto(
                descriptor.id(),
                descriptor.name(),
                descriptor.type(),
                descriptor.complexity(),
                descriptor.executionShape(),
                descriptor.expectedOutcome(),
                descriptor.dataProfiles(),
                descriptor.recommendedProtocols(),
                descriptor.recommendedOperations(),
                descriptor.description(),
                descriptor.researchPurpose(),
                descriptor.requiresDocker(),
                descriptor.requiresWorkspaceArtifact(),
                descriptor.requiresMerge(),
                descriptor.suggestedShardCount(),
                descriptor.suggestedTimeoutSeconds(),
                descriptor.tags()
        );
    }
}
