package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExecutionShape;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadValidationReasonCode;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ResearchWorkloadCatalogValidator {

    private final ResearchWorkloadCatalogService catalogService;
    private final ResearchProtocolContractValidator protocolValidator;
    private final ResearchProtocolRegistry protocolRegistry;

    public AdminResearchWorkloadValidationResponseDto validate(AdminResearchWorkloadValidationRequestDto request) {
        AdminResearchWorkloadValidationRequestDto validRequest = requireRequest(request);
        return catalogService.findDescriptor(validRequest.workloadId())
                .map(workload -> validateKnownWorkload(workload, validRequest))
                .orElseGet(() -> unsupported(
                        ResearchWorkloadValidationReasonCode.UNKNOWN_WORKLOAD,
                        "Unknown research workload."
                ));
    }

    private AdminResearchWorkloadValidationResponseDto validateKnownWorkload(
            ResearchWorkloadDescriptor workload,
            AdminResearchWorkloadValidationRequestDto request
    ) {
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
                    ResearchWorkloadValidationReasonCode.PROTOCOL_COMBINATION_NOT_SUPPORTED,
                    "Protocol combination is not supported by the research protocol contract."
            );
        }

        AdminResearchWorkloadValidationResponseDto shapeResult = validateShape(workload, request.operation());
        if (shapeResult != null) {
            return shapeResult;
        }

        if (workload.requiresWorkspaceArtifact()
                && isCreationOperation(request.operation())
                && !protocolSupportsWorkspaceArtifact(request)) {
            return unsupported(
                    ResearchWorkloadValidationReasonCode.WORKLOAD_REQUIRES_WORKSPACE_ARTIFACT,
                    "Workload requires a workspace artifact."
            );
        }

        return new AdminResearchWorkloadValidationResponseDto(
                true,
                null,
                "Workload can be used with the selected protocol combination."
        );
    }

    private static AdminResearchWorkloadValidationRequestDto requireRequest(
            AdminResearchWorkloadValidationRequestDto request
    ) {
        AdminResearchWorkloadValidationRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        if (validRequest.workloadId() == null || validRequest.workloadId().isBlank()) {
            throw new IllegalArgumentException("workloadId is required.");
        }
        Objects.requireNonNull(validRequest.protocol(), "protocol is required.");
        Objects.requireNonNull(validRequest.operation(), "operation is required.");
        Objects.requireNonNull(validRequest.dataTransferMode(), "dataTransferMode is required.");
        Objects.requireNonNull(validRequest.payloadFormat(), "payloadFormat is required.");
        return validRequest;
    }

    private static AdminResearchWorkloadValidationResponseDto validateShape(
            ResearchWorkloadDescriptor workload,
            ResearchOperation operation
    ) {
        if (workload.executionShape() == ResearchWorkloadExecutionShape.SINGLE_EXECUTION
                && isGroupOnlyOperation(operation)) {
            return unsupported(
                    ResearchWorkloadValidationReasonCode.WORKLOAD_REQUIRES_SINGLE_EXECUTION_OPERATION,
                    "Workload requires a single-execution operation."
            );
        }

        if (isGroupShape(workload.executionShape()) && isSingleExecutionOnlyOperation(operation)) {
            return unsupported(
                    ResearchWorkloadValidationReasonCode.WORKLOAD_REQUIRES_GROUP_OPERATION,
                    "Workload requires an execution-group operation."
            );
        }

        if (workload.executionShape() == ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE
                && operation == ResearchOperation.CREATE_SINGLE_EXECUTION) {
            return unsupported(
                    ResearchWorkloadValidationReasonCode.WORKLOAD_REQUIRES_MERGE_SUPPORT,
                    "Workload requires Agent merge support."
            );
        }

        return null;
    }

    private static boolean isGroupShape(ResearchWorkloadExecutionShape executionShape) {
        return executionShape == ResearchWorkloadExecutionShape.EXECUTION_GROUP
                || executionShape == ResearchWorkloadExecutionShape.EXECUTION_GROUP_WITH_AGENT_MERGE;
    }

    private static boolean isCreationOperation(ResearchOperation operation) {
        return operation == ResearchOperation.CREATE_SINGLE_EXECUTION
                || operation == ResearchOperation.CREATE_EXECUTION_GROUP;
    }

    private static boolean isSingleExecutionOnlyOperation(ResearchOperation operation) {
        return operation == ResearchOperation.CREATE_SINGLE_EXECUTION
                || operation == ResearchOperation.GET_EXECUTION_STATUS;
    }

    private static boolean isGroupOnlyOperation(ResearchOperation operation) {
        return operation == ResearchOperation.CREATE_EXECUTION_GROUP
                || operation == ResearchOperation.GET_GROUP_DETAIL
                || operation == ResearchOperation.GET_GROUP_ACTIVITY
                || operation == ResearchOperation.GET_GROUP_ARTIFACTS
                || operation == ResearchOperation.STREAM_GROUP_ACTIVITY
                || operation == ResearchOperation.STOP_STREAM_GROUP_ACTIVITY
                || operation == ResearchOperation.CANCEL_GROUP
                || operation == ResearchOperation.RECONCILE_GROUP;
    }

    private static boolean isInlineOnly(ResearchDataTransferMode dataTransferMode) {
        return dataTransferMode == ResearchDataTransferMode.INLINE_JSON
                || dataTransferMode == ResearchDataTransferMode.INLINE_XML;
    }

    private boolean protocolSupportsWorkspaceArtifact(AdminResearchWorkloadValidationRequestDto request) {
        if (!isInlineOnly(request.dataTransferMode())) {
            return true;
        }

        return protocolRegistry.protocol(request.protocol())
                .map(protocol -> protocol.supportedDataTransferModes().contains(
                        ResearchDataTransferMode.WORKSPACE_ARTIFACT
                ))
                .orElse(false);
    }

    private static AdminResearchWorkloadValidationResponseDto unsupported(
            ResearchWorkloadValidationReasonCode reasonCode,
            String reasonMessage
    ) {
        return new AdminResearchWorkloadValidationResponseDto(false, reasonCode, reasonMessage);
    }
}
