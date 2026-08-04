package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocolStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ResearchProtocolRegistry {

    private final List<ResearchProtocolDescriptor> protocols = List.of(
            restDescriptor(),
            websocketDescriptor(),
            soapDescriptor()
    );
    private final Map<ResearchProtocol, ResearchProtocolDescriptor> protocolsByName = protocols.stream()
            .collect(Collectors.toUnmodifiableMap(ResearchProtocolDescriptor::protocol, Function.identity()));
    private final List<ResearchOperationDescriptor> operations = List.of(
            new ResearchOperationDescriptor(
                    ResearchOperation.CREATE_SINGLE_EXECUTION,
                    "Create one admin-triggered WorkExecution.",
                    true,
                    "WORK_EXECUTION"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.CREATE_EXECUTION_GROUP,
                    "Create a sharded execution group using a WorkDefinitionVersion.",
                    true,
                    "EXECUTION_GROUP"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.GET_EXECUTION_STATUS,
                    "Read execution status and safe execution metadata.",
                    false,
                    "EXECUTION_STATUS"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.GET_GROUP_DETAIL,
                    "Read safe execution group detail and observability summary.",
                    false,
                    "EXECUTION_GROUP_DETAIL"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.GET_GROUP_ACTIVITY,
                    "Read the derived execution group activity feed.",
                    false,
                    "EXECUTION_GROUP_ACTIVITY"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.GET_GROUP_ARTIFACTS,
                    "Read safe output artifact metadata for an execution group.",
                    false,
                    "EXECUTION_GROUP_ARTIFACTS"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.STREAM_GROUP_ACTIVITY,
                    "Stream safe execution group activity updates for admin live views.",
                    false,
                    "SSE_STREAM"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.DOWNLOAD_ARTIFACT,
                    "Download one output artifact through the admin artifact API.",
                    false,
                    "ARTIFACT_BYTES"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.CANCEL_GROUP,
                    "Request cancellation for an execution group.",
                    true,
                    "EXECUTION_GROUP"
            ),
            new ResearchOperationDescriptor(
                    ResearchOperation.RECONCILE_GROUP,
                    "Run one manual reconciliation pass for an execution group.",
                    true,
                    "EXECUTION_GROUP"
            )
    );
    private final List<ResearchDataTransferModeDescriptor> dataTransferModes = List.of(
            new ResearchDataTransferModeDescriptor(
                    ResearchDataTransferMode.INLINE_JSON,
                    "Request and response data are transferred as JSON payloads."
            ),
            new ResearchDataTransferModeDescriptor(
                    ResearchDataTransferMode.INLINE_XML,
                    "Request and response data are transferred as XML payloads."
            ),
            new ResearchDataTransferModeDescriptor(
                    ResearchDataTransferMode.WORKSPACE_ARTIFACT,
                    "Input data is provided through a workspace artifact."
            ),
            new ResearchDataTransferModeDescriptor(
                    ResearchDataTransferMode.OUTPUT_ARTIFACT,
                    "Result data is retrieved as an output artifact."
            ),
            new ResearchDataTransferModeDescriptor(
                    ResearchDataTransferMode.STREAMED_EVENTS,
                    "Updates are transferred as a server-to-client event stream."
            )
    );
    private final List<ResearchPayloadFormatDescriptor> payloadFormats = List.of(
            new ResearchPayloadFormatDescriptor(
                    ResearchPayloadFormat.JSON,
                    "Structured JSON payload."
            ),
            new ResearchPayloadFormatDescriptor(
                    ResearchPayloadFormat.XML,
                    "Structured XML or SOAP payload."
            ),
            new ResearchPayloadFormatDescriptor(
                    ResearchPayloadFormat.BINARY,
                    "Binary artifact or byte stream payload."
            ),
            new ResearchPayloadFormatDescriptor(
                    ResearchPayloadFormat.MULTIPART,
                    "Multipart HTTP payload used for file transfer."
            )
    );

    List<ResearchProtocolDescriptor> protocols() {
        return protocols;
    }

    Optional<ResearchProtocolDescriptor> protocol(ResearchProtocol protocol) {
        return Optional.ofNullable(protocolsByName.get(protocol));
    }

    List<ResearchOperationDescriptor> operations() {
        return operations;
    }

    List<ResearchDataTransferModeDescriptor> dataTransferModes() {
        return dataTransferModes;
    }

    List<ResearchPayloadFormatDescriptor> payloadFormats() {
        return payloadFormats;
    }

    private static ResearchProtocolDescriptor restDescriptor() {
        Set<ResearchProtocolCombination> combinations = Set.of(
                combination(
                        ResearchOperation.CREATE_SINGLE_EXECUTION,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.CREATE_EXECUTION_GROUP,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.GET_EXECUTION_STATUS,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.GET_GROUP_DETAIL,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.GET_GROUP_ACTIVITY,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.GET_GROUP_ARTIFACTS,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.STREAM_GROUP_ACTIVITY,
                        ResearchDataTransferMode.STREAMED_EVENTS,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.DOWNLOAD_ARTIFACT,
                        ResearchDataTransferMode.OUTPUT_ARTIFACT,
                        ResearchPayloadFormat.BINARY
                ),
                combination(
                        ResearchOperation.CANCEL_GROUP,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                ),
                combination(
                        ResearchOperation.RECONCILE_GROUP,
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchPayloadFormat.JSON
                )
        );

        return new ResearchProtocolDescriptor(
                ResearchProtocol.REST,
                ResearchProtocolStatus.AVAILABLE,
                "Existing HTTP/JSON admin API baseline.",
                EnumSet.of(ResearchPayloadFormat.JSON, ResearchPayloadFormat.BINARY, ResearchPayloadFormat.MULTIPART),
                EnumSet.of(
                        ResearchDataTransferMode.INLINE_JSON,
                        ResearchDataTransferMode.WORKSPACE_ARTIFACT,
                        ResearchDataTransferMode.OUTPUT_ARTIFACT,
                        ResearchDataTransferMode.STREAMED_EVENTS
                ),
                EnumSet.allOf(ResearchOperation.class),
                combinations
        );
    }

    private static ResearchProtocolDescriptor websocketDescriptor() {
        return new ResearchProtocolDescriptor(
                ResearchProtocol.WEBSOCKET,
                ResearchProtocolStatus.PLANNED,
                "Planned research adapter for bidirectional real-time communication.",
                EnumSet.of(ResearchPayloadFormat.JSON),
                EnumSet.of(ResearchDataTransferMode.INLINE_JSON, ResearchDataTransferMode.STREAMED_EVENTS),
                Set.of(),
                Set.of()
        );
    }

    private static ResearchProtocolDescriptor soapDescriptor() {
        return new ResearchProtocolDescriptor(
                ResearchProtocol.SOAP,
                ResearchProtocolStatus.PLANNED,
                "Planned research adapter for XML/SOAP enterprise-style communication.",
                EnumSet.of(ResearchPayloadFormat.XML),
                EnumSet.of(
                        ResearchDataTransferMode.INLINE_XML,
                        ResearchDataTransferMode.WORKSPACE_ARTIFACT,
                        ResearchDataTransferMode.OUTPUT_ARTIFACT
                ),
                Set.of(),
                Set.of()
        );
    }

    private static ResearchProtocolCombination combination(ResearchOperation operation,
                                                           ResearchDataTransferMode dataTransferMode,
                                                           ResearchPayloadFormat payloadFormat) {
        return new ResearchProtocolCombination(operation, dataTransferMode, payloadFormat);
    }
}
