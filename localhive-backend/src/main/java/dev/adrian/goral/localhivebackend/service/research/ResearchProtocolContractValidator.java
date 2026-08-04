package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocolStatus;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocolValidationReasonCode;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ResearchProtocolContractValidator {

    private final ResearchProtocolRegistry registry;

    public AdminResearchProtocolValidationResponseDto validate(AdminResearchProtocolValidationRequestDto request) {
        AdminResearchProtocolValidationRequestDto validRequest = requireRequest(request);
        ResearchProtocolDescriptor protocol = registry.protocol(validRequest.protocol())
                .orElseThrow(() -> new IllegalArgumentException("Unknown research protocol."));

        if (protocol.status() == ResearchProtocolStatus.PLANNED) {
            return unsupported(
                    ResearchProtocolValidationReasonCode.PROTOCOL_PLANNED,
                    "Protocol " + validRequest.protocol() + " is planned but not available yet."
            );
        }
        if (protocol.status() == ResearchProtocolStatus.DISABLED) {
            return unsupported(
                    ResearchProtocolValidationReasonCode.PROTOCOL_DISABLED,
                    "Protocol " + validRequest.protocol() + " is disabled."
            );
        }
        if (!protocol.supportedOperations().contains(validRequest.operation())) {
            return unsupported(
                    ResearchProtocolValidationReasonCode.OPERATION_NOT_SUPPORTED,
                    "Operation " + validRequest.operation() + " is not supported by protocol "
                            + validRequest.protocol() + "."
            );
        }
        if (!protocol.supportedDataTransferModes().contains(validRequest.dataTransferMode())) {
            return unsupported(
                    ResearchProtocolValidationReasonCode.DATA_TRANSFER_MODE_NOT_SUPPORTED,
                    "Data transfer mode " + validRequest.dataTransferMode() + " is not supported by protocol "
                            + validRequest.protocol() + "."
            );
        }
        if (!protocol.supportedPayloadFormats().contains(validRequest.payloadFormat())) {
            return unsupported(
                    ResearchProtocolValidationReasonCode.PAYLOAD_FORMAT_NOT_SUPPORTED,
                    "Payload format " + validRequest.payloadFormat() + " is not supported by protocol "
                            + validRequest.protocol() + "."
            );
        }

        ResearchProtocolCombination combination = new ResearchProtocolCombination(
                validRequest.operation(),
                validRequest.dataTransferMode(),
                validRequest.payloadFormat()
        );
        if (!protocol.supportedCombinations().contains(combination)) {
            return unsupported(
                    ResearchProtocolValidationReasonCode.COMBINATION_NOT_SUPPORTED,
                    "Combination is not supported by protocol " + validRequest.protocol() + "."
            );
        }

        return new AdminResearchProtocolValidationResponseDto(
                true,
                null,
                "Combination is supported."
        );
    }

    private static AdminResearchProtocolValidationRequestDto requireRequest(
            AdminResearchProtocolValidationRequestDto request
    ) {
        AdminResearchProtocolValidationRequestDto validRequest = Objects.requireNonNull(
                request,
                "request must not be null."
        );
        Objects.requireNonNull(validRequest.protocol(), "protocol is required.");
        Objects.requireNonNull(validRequest.operation(), "operation is required.");
        Objects.requireNonNull(validRequest.dataTransferMode(), "dataTransferMode is required.");
        Objects.requireNonNull(validRequest.payloadFormat(), "payloadFormat is required.");
        return validRequest;
    }

    private static AdminResearchProtocolValidationResponseDto unsupported(
            ResearchProtocolValidationReasonCode reasonCode,
            String reasonMessage
    ) {
        return new AdminResearchProtocolValidationResponseDto(false, reasonCode, reasonMessage);
    }
}
