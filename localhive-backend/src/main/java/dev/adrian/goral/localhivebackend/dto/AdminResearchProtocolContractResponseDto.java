package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocolStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AdminResearchProtocolContractResponseDto(
        LocalDateTime generatedAt,
        List<ProtocolDescriptorResponseDto> protocols,
        List<OperationDescriptorResponseDto> operations,
        List<DataTransferModeDescriptorResponseDto> dataTransferModes,
        List<PayloadFormatDescriptorResponseDto> payloadFormats
) {

    public AdminResearchProtocolContractResponseDto {
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
        operations = operations == null ? List.of() : List.copyOf(operations);
        dataTransferModes = dataTransferModes == null ? List.of() : List.copyOf(dataTransferModes);
        payloadFormats = payloadFormats == null ? List.of() : List.copyOf(payloadFormats);
    }

    public record ProtocolDescriptorResponseDto(
            ResearchProtocol protocol,
            ResearchProtocolStatus status,
            String description,
            List<ResearchPayloadFormat> supportedPayloadFormats,
            List<ResearchDataTransferMode> supportedDataTransferModes,
            List<ResearchOperation> supportedOperations
    ) {

        public ProtocolDescriptorResponseDto {
            supportedPayloadFormats = supportedPayloadFormats == null
                    ? List.of()
                    : List.copyOf(supportedPayloadFormats);
            supportedDataTransferModes = supportedDataTransferModes == null
                    ? List.of()
                    : List.copyOf(supportedDataTransferModes);
            supportedOperations = supportedOperations == null ? List.of() : List.copyOf(supportedOperations);
        }
    }

    public record OperationDescriptorResponseDto(
            ResearchOperation operation,
            String description,
            boolean mutating,
            String resultType
    ) {
    }

    public record DataTransferModeDescriptorResponseDto(
            ResearchDataTransferMode mode,
            String description
    ) {
    }

    public record PayloadFormatDescriptorResponseDto(
            ResearchPayloadFormat format,
            String description
    ) {
    }
}
