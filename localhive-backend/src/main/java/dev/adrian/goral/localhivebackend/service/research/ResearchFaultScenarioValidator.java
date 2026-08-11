package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultScenarioValidationReasonCode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExecutionShape;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadType;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioValidationResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ResearchFaultScenarioValidator {

    private static final String UNSUPPORTED_PROTOCOL_COMBINATION = "UNSUPPORTED_PROTOCOL_COMBINATION";

    private final ResearchFaultScenarioCatalogService scenarioCatalogService;
    private final ResearchWorkloadCatalogService workloadCatalogService;
    private final ResearchProtocolContractValidator protocolValidator;
    private final ResearchWorkloadCatalogValidator workloadValidator;

    public AdminResearchFaultScenarioValidationResponseDto validate(
            AdminResearchFaultScenarioValidationRequestDto request
    ) {
        AdminResearchFaultScenarioValidationRequestDto validRequest = requireRequest(request);

        return scenarioCatalogService.findDescriptor(validRequest.scenarioId())
                .map(scenario -> validateKnownScenario(scenario, validRequest))
                .orElseGet(() -> unsupported(
                        ResearchFaultScenarioValidationReasonCode.UNKNOWN_FAULT_SCENARIO,
                        "Unknown research fault scenario."
                ));
    }

    private AdminResearchFaultScenarioValidationResponseDto validateKnownScenario(
            ResearchFaultScenarioDescriptor scenario,
            AdminResearchFaultScenarioValidationRequestDto request
    ) {
        return workloadCatalogService.findDescriptor(request.workloadId())
                .map(workload -> validateKnownScenarioAndWorkload(scenario, workload, request))
                .orElseGet(() -> unsupported(
                        ResearchFaultScenarioValidationReasonCode.UNKNOWN_WORKLOAD,
                        "Unknown research workload."
                ));
    }

    private AdminResearchFaultScenarioValidationResponseDto validateKnownScenarioAndWorkload(
            ResearchFaultScenarioDescriptor scenario,
            ResearchWorkloadDescriptor workload,
            AdminResearchFaultScenarioValidationRequestDto request
    ) {
        if (UNSUPPORTED_PROTOCOL_COMBINATION.equals(scenario.id())) {
            return validateUnsupportedProtocolCombination(request);
        }

        AdminResearchFaultScenarioValidationResponseDto scenarioProtocolResult =
                validateScenarioProtocolRequirements(scenario.id(), request);
        if (scenarioProtocolResult != null) {
            return scenarioProtocolResult;
        }

        AdminResearchProtocolValidationResponseDto protocolResult = protocolValidator.validate(
                new AdminResearchProtocolValidationRequestDto(
                        request.protocol(),
                        request.operation(),
                        request.dataTransferMode(),
                        request.payloadFormat()
                )
        );
        if (!protocolResult.valid()) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.PROTOCOL_COMBINATION_NOT_SUPPORTED,
                    "Protocol combination is not supported by the research protocol contract."
            );
        }

        AdminResearchWorkloadValidationResponseDto workloadResult = workloadValidator.validate(
                new AdminResearchWorkloadValidationRequestDto(
                        request.workloadId(),
                        request.protocol(),
                        request.operation(),
                        request.dataTransferMode(),
                        request.payloadFormat()
                )
        );
        if (!workloadResult.valid()) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.WORKLOAD_COMBINATION_NOT_SUPPORTED,
                    "Workload combination is not supported by the research workload catalog."
            );
        }

        AdminResearchFaultScenarioValidationResponseDto scenarioWorkloadResult =
                validateScenarioWorkloadRequirements(scenario.id(), workload, request.operation());
        if (scenarioWorkloadResult != null) {
            return scenarioWorkloadResult;
        }

        return supported("Fault scenario can be used with the selected workload and protocol combination.");
    }

    private AdminResearchFaultScenarioValidationResponseDto validateUnsupportedProtocolCombination(
            AdminResearchFaultScenarioValidationRequestDto request
    ) {
        AdminResearchProtocolValidationResponseDto protocolResult = protocolValidator.validate(
                new AdminResearchProtocolValidationRequestDto(
                        request.protocol(),
                        request.operation(),
                        request.dataTransferMode(),
                        request.payloadFormat()
                )
        );
        if (protocolResult.valid()) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.COMBINATION_NOT_RECOMMENDED,
                    "Fault scenario requires an unsupported protocol combination."
            );
        }

        return supported("Fault scenario can be used to verify safe rejection of an unsupported protocol combination.");
    }

    private static AdminResearchFaultScenarioValidationResponseDto validateScenarioProtocolRequirements(
            String scenarioId,
            AdminResearchFaultScenarioValidationRequestDto request
    ) {
        if ("INVALID_REST_PAYLOAD".equals(scenarioId) && request.protocol() != ResearchProtocol.REST) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.FAULT_REQUIRES_REST,
                    "Fault scenario requires REST."
            );
        }

        if ("MALFORMED_SOAP_REQUEST".equals(scenarioId) && request.protocol() != ResearchProtocol.SOAP) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.FAULT_REQUIRES_SOAP,
                    "Fault scenario requires SOAP."
            );
        }

        if ("BROKEN_WEBSOCKET_STREAM".equals(scenarioId)) {
            if (request.protocol() != ResearchProtocol.WEBSOCKET) {
                return unsupported(
                        ResearchFaultScenarioValidationReasonCode.FAULT_REQUIRES_WEBSOCKET,
                        "Fault scenario requires WebSocket."
                );
            }
            if (request.operation() != ResearchOperation.STREAM_GROUP_ACTIVITY
                    || request.dataTransferMode() != ResearchDataTransferMode.STREAMED_EVENTS) {
                return unsupported(
                        ResearchFaultScenarioValidationReasonCode.FAULT_REQUIRES_STREAMING,
                        "Fault scenario requires group activity streaming."
                );
            }
        }

        return null;
    }

    private static AdminResearchFaultScenarioValidationResponseDto validateScenarioWorkloadRequirements(
            String scenarioId,
            ResearchWorkloadDescriptor workload,
            ResearchOperation operation
    ) {
        return switch (scenarioId) {
            case "WORKER_OFFLINE_DURING_EXECUTION" -> validateWorkerOfflineWorkload(workload);
            case "TASK_FAILURE_EXIT_CODE" -> validateWorkloadType(workload, ResearchWorkloadType.FAILING_TASK);
            case "MERGE_FAILURE_AGENT" -> validateMergeFailureWorkload(workload);
            case "CANCELLED_GROUP_QUEUED" -> validateCancelledGroupWorkload(workload, operation);
            case "LONG_RUNNING_TIMEOUT" -> validateWorkloadType(workload, ResearchWorkloadType.LONG_RUNNING);
            default -> null;
        };
    }

    private static AdminResearchFaultScenarioValidationResponseDto validateWorkerOfflineWorkload(
            ResearchWorkloadDescriptor workload
    ) {
        if (isGroupShape(workload.executionShape())) {
            return null;
        }

        return unsupported(
                ResearchFaultScenarioValidationReasonCode.FAULT_REQUIRES_GROUP_OPERATION,
                "Fault scenario requires an execution-group workload."
        );
    }

    private static AdminResearchFaultScenarioValidationResponseDto validateMergeFailureWorkload(
            ResearchWorkloadDescriptor workload
    ) {
        if (workload.type() == ResearchWorkloadType.AGENT_MERGE
                && workload.executionShape() == ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE
                && workload.requiresMerge()) {
            return null;
        }

        return unsupported(
                ResearchFaultScenarioValidationReasonCode.FAULT_NOT_COMPATIBLE_WITH_WORKLOAD,
                "Fault scenario requires an Agent merge workload."
        );
    }

    private static AdminResearchFaultScenarioValidationResponseDto validateCancelledGroupWorkload(
            ResearchWorkloadDescriptor workload,
            ResearchOperation operation
    ) {
        if (!isGroupShape(workload.executionShape())) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.FAULT_REQUIRES_GROUP_OPERATION,
                    "Fault scenario requires an execution-group workload."
            );
        }

        if (operation != ResearchOperation.CREATE_EXECUTION_GROUP
                && operation != ResearchOperation.CANCEL_GROUP
                && operation != ResearchOperation.GET_GROUP_DETAIL
                && operation != ResearchOperation.GET_GROUP_ACTIVITY) {
            return unsupported(
                    ResearchFaultScenarioValidationReasonCode.FAULT_NOT_COMPATIBLE_WITH_OPERATION,
                    "Fault scenario is not compatible with the selected operation."
            );
        }

        if (workload.type() == ResearchWorkloadType.CANCELLED_GROUP) {
            return null;
        }

        return unsupported(
                ResearchFaultScenarioValidationReasonCode.FAULT_NOT_COMPATIBLE_WITH_WORKLOAD,
                "Fault scenario requires a cancellation workload."
        );
    }

    private static AdminResearchFaultScenarioValidationResponseDto validateWorkloadType(
            ResearchWorkloadDescriptor workload,
            ResearchWorkloadType requiredType
    ) {
        if (workload.type() == requiredType) {
            return null;
        }

        return unsupported(
                ResearchFaultScenarioValidationReasonCode.FAULT_NOT_COMPATIBLE_WITH_WORKLOAD,
                "Fault scenario is not compatible with the selected workload."
        );
    }

    private static boolean isGroupShape(ResearchWorkloadExecutionShape executionShape) {
        return executionShape == ResearchWorkloadExecutionShape.EXECUTION_GROUP
                || executionShape == ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE;
    }

    private static AdminResearchFaultScenarioValidationRequestDto requireRequest(
            AdminResearchFaultScenarioValidationRequestDto request
    ) {
        AdminResearchFaultScenarioValidationRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        if (validRequest.scenarioId() == null || validRequest.scenarioId().isBlank()) {
            throw new IllegalArgumentException("scenarioId is required.");
        }
        if (validRequest.workloadId() == null || validRequest.workloadId().isBlank()) {
            throw new IllegalArgumentException("workloadId is required.");
        }
        Objects.requireNonNull(validRequest.protocol(), "protocol is required.");
        Objects.requireNonNull(validRequest.operation(), "operation is required.");
        Objects.requireNonNull(validRequest.dataTransferMode(), "dataTransferMode is required.");
        Objects.requireNonNull(validRequest.payloadFormat(), "payloadFormat is required.");
        return validRequest;
    }

    private static AdminResearchFaultScenarioValidationResponseDto supported(String reasonMessage) {
        return new AdminResearchFaultScenarioValidationResponseDto(
                true,
                ResearchFaultScenarioValidationReasonCode.SUPPORTED,
                reasonMessage
        );
    }

    private static AdminResearchFaultScenarioValidationResponseDto unsupported(
            ResearchFaultScenarioValidationReasonCode reasonCode,
            String reasonMessage
    ) {
        return new AdminResearchFaultScenarioValidationResponseDto(false, reasonCode, reasonMessage);
    }
}
