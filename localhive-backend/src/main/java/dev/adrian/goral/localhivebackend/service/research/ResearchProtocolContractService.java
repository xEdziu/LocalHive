package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolContractResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class ResearchProtocolContractService {

    private final ResearchProtocolRegistry registry;

    public AdminResearchProtocolContractResponseDto getContract() {
        return new AdminResearchProtocolContractResponseDto(
                LocalDateTime.now(),
                registry.protocols().stream()
                        .map(descriptor -> new AdminResearchProtocolContractResponseDto.ProtocolDescriptorResponseDto(
                                descriptor.protocol(),
                                descriptor.status(),
                                descriptor.description(),
                                descriptor.supportedPayloadFormats().stream()
                                        .sorted(Comparator.comparing(Enum::name))
                                        .toList(),
                                descriptor.supportedDataTransferModes().stream()
                                        .sorted(Comparator.comparing(Enum::name))
                                        .toList(),
                                descriptor.supportedOperations().stream()
                                        .sorted(Comparator.comparing(Enum::name))
                                        .toList()
                        ))
                        .toList(),
                registry.operations().stream()
                        .map(descriptor -> new AdminResearchProtocolContractResponseDto.OperationDescriptorResponseDto(
                                descriptor.operation(),
                                descriptor.description(),
                                descriptor.mutating(),
                                descriptor.resultType()
                        ))
                        .toList(),
                registry.dataTransferModes().stream()
                        .map(descriptor -> new AdminResearchProtocolContractResponseDto.DataTransferModeDescriptorResponseDto(
                                descriptor.mode(),
                                descriptor.description()
                        ))
                        .toList(),
                registry.payloadFormats().stream()
                        .map(descriptor -> new AdminResearchProtocolContractResponseDto.PayloadFormatDescriptorResponseDto(
                                descriptor.format(),
                                descriptor.description()
                        ))
                        .toList()
        );
    }
}
