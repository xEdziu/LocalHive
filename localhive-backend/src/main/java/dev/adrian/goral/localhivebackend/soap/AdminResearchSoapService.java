package dev.adrian.goral.localhivebackend.soap;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchSoapErrorReasonCode;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupActivityResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupArtifactSummaryResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupArtifactsResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupControlService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminResearchSoapService {

    public static final String SOAP_NAMESPACE = "https://localhive.dev/research/soap";

    private static final String SOAP_11_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP_12_NAMESPACE = "http://www.w3.org/2003/05/soap-envelope";
    private static final String SOAP_PREFIX = "soapenv";
    private static final String LOCALHIVE_PREFIX = "lh";
    private static final Map<ResearchOperation, SoapOperation> SUPPORTED_OPERATIONS = supportedOperations();
    private static final Map<String, String> UNSUPPORTED_REQUESTS = Map.of(
            "CreateExecutionGroupRequest", "CreateExecutionGroupResponse",
            "DownloadArtifactRequest", "DownloadArtifactResponse",
            "StreamGroupActivityRequest", "StreamGroupActivityResponse",
            "UploadWorkspaceArtifactRequest", "UploadWorkspaceArtifactResponse"
    );

    private final AdminExecutionGroupQueryService queryService;
    private final AdminExecutionGroupControlService controlService;

    public SoapResponse handle(String requestBody) {
        try {
            SoapRequest request = parse(requestBody);
            if (request.operation() == null) {
                return unsupportedOrUnknown(request.operationName());
            }
            SoapOperation operation = SUPPORTED_OPERATIONS.get(request.operation());
            if (operation == null) {
                return unsupportedOrUnknown(request.operationName());
            }
            CURRENT_RESPONSE_NAME.set(operation.responseName());
            return success(operation.responseName(), dispatch(operation.operation(), request.operationElement()));
        } catch (MalformedSoapException e) {
            return fault(HttpStatus.BAD_REQUEST, ResearchSoapErrorReasonCode.MALFORMED_MESSAGE, e.getMessage());
        } catch (InvalidPayloadException e) {
            return applicationError(e.responseName(), ResearchSoapErrorReasonCode.INVALID_PAYLOAD, e.getMessage());
        } catch (NoSuchElementException e) {
            return applicationError(
                    currentResponseName(e),
                    ResearchSoapErrorReasonCode.GROUP_NOT_FOUND,
                    "Execution group not found."
            );
        } catch (IllegalStateException e) {
            return applicationError(
                    currentResponseName(e),
                    ResearchSoapErrorReasonCode.OPERATION_CONFLICT,
                    e.getMessage()
            );
        } catch (IllegalArgumentException e) {
            return applicationError(
                    currentResponseName(e),
                    ResearchSoapErrorReasonCode.INVALID_PAYLOAD,
                    e.getMessage()
            );
        } catch (Exception e) {
            log.warn("Admin research SOAP operation failed: {}", e.toString());
            return applicationError(
                    "SoapErrorResponse",
                    ResearchSoapErrorReasonCode.INTERNAL_ERROR,
                    "SOAP operation failed."
            );
        } finally {
            CURRENT_RESPONSE_NAME.remove();
        }
    }

    private Object dispatch(ResearchOperation operation, Element requestElement) {
        UUID executionGroupId = readExecutionGroupId(requestElement);
        return switch (operation) {
            case GET_GROUP_DETAIL -> groupDetail(executionGroupId);
            case GET_GROUP_ACTIVITY -> groupActivity(executionGroupId);
            case GET_GROUP_ARTIFACTS -> groupArtifacts(executionGroupId);
            case CANCEL_GROUP -> {
                controlService.cancelGroup(executionGroupId, readOptionalText(requestElement, "reason"), LocalDateTime.now());
                yield groupDetail(executionGroupId);
            }
            case RECONCILE_GROUP -> {
                controlService.reconcileGroup(executionGroupId, LocalDateTime.now());
                yield groupDetail(executionGroupId);
            }
            default -> throw new IllegalStateException("Unsupported SOAP operation.");
        };
    }

    private AdminExecutionGroupDetailResponseDto groupDetail(UUID executionGroupId) {
        return queryService.getGroup(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }

    private AdminExecutionGroupActivityResponseDto groupActivity(UUID executionGroupId) {
        return queryService.getGroupActivity(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }

    private AdminExecutionGroupArtifactsResponseDto groupArtifacts(UUID executionGroupId) {
        return queryService.listGroupArtifacts(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }

    private SoapResponse unsupportedOrUnknown(String operationName) {
        String responseName = UNSUPPORTED_REQUESTS.get(operationName);
        if (responseName == null) {
            return applicationError(
                    "SoapErrorResponse",
                    ResearchSoapErrorReasonCode.UNKNOWN_OPERATION,
                    "Unsupported SOAP operation."
            );
        }

        return applicationError(
                responseName,
                ResearchSoapErrorReasonCode.OPERATION_NOT_SUPPORTED,
                "Unsupported SOAP operation."
        );
    }

    private SoapRequest parse(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            throw new MalformedSoapException("SOAP request body is required.");
        }

        Document document;
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new SilentSoapErrorHandler());
            document = builder.parse(new InputSource(new StringReader(requestBody)));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("SOAP parser cannot be configured.", e);
        } catch (SAXException | IOException e) {
            throw new MalformedSoapException("Malformed SOAP/XML message.");
        }

        Element envelope = document.getDocumentElement();
        if (envelope == null || !"Envelope".equals(localName(envelope)) || !isSoapNamespace(envelope.getNamespaceURI())) {
            throw new MalformedSoapException("SOAP envelope is required.");
        }

        Element body = firstChildElement(envelope, "Body");
        if (body == null) {
            throw new MalformedSoapException("SOAP body is required.");
        }

        Element operationElement = firstChildElement(body);
        if (operationElement == null) {
            throw new MalformedSoapException("SOAP body must contain an operation request.");
        }
        if (!SOAP_NAMESPACE.equals(operationElement.getNamespaceURI())) {
            return new SoapRequest(null, localName(operationElement), operationElement);
        }

        String operationName = localName(operationElement);
        SoapOperation operation = SUPPORTED_OPERATIONS.values()
                .stream()
                .filter(candidate -> candidate.requestName().equals(operationName))
                .findFirst()
                .orElse(null);
        return new SoapRequest(operation == null ? null : operation.operation(), operationName, operationElement);
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private UUID readExecutionGroupId(Element element) {
        String rawValue = readRequiredText(element, "executionGroupId");
        try {
            return UUID.fromString(rawValue);
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException(
                    currentResponseName(),
                    "executionGroupId must be a valid UUID."
            );
        }
    }

    private String readRequiredText(Element element, String localName) {
        String value = readOptionalText(element, localName);
        if (value == null || value.isBlank()) {
            throw new InvalidPayloadException(currentResponseName(), localName + " is required.");
        }
        return value.trim();
    }

    private static String readOptionalText(Element element, String localName) {
        Element child = firstChildElement(element, localName);
        if (child == null) {
            return null;
        }
        return child.getTextContent();
    }

    private SoapResponse success(String responseName, Object data) {
        StringBuilder body = new StringBuilder();
        startElement(body, responseName);
        appendElement(body, "success", true);
        body.append("<").append(LOCALHIVE_PREFIX).append(":data>");
        appendData(body, data);
        body.append("</").append(LOCALHIVE_PREFIX).append(":data>");
        endElement(body, responseName);
        return new SoapResponse(envelope(body.toString()), HttpStatus.OK);
    }

    private SoapResponse applicationError(String responseName,
                                          ResearchSoapErrorReasonCode reasonCode,
                                          String message) {
        StringBuilder body = new StringBuilder();
        startElement(body, responseName);
        appendElement(body, "success", false);
        appendError(body, reasonCode, safeMessage(message));
        endElement(body, responseName);
        return new SoapResponse(envelope(body.toString()), HttpStatus.OK);
    }

    private SoapResponse fault(HttpStatus status, ResearchSoapErrorReasonCode reasonCode, String message) {
        StringBuilder fault = new StringBuilder();
        fault.append("<").append(SOAP_PREFIX).append(":Fault>");
        appendPlainElement(fault, "faultcode", SOAP_PREFIX + ":Client");
        appendPlainElement(fault, "faultstring", safeMessage(message));
        fault.append("<detail>");
        appendError(fault, reasonCode, safeMessage(message));
        fault.append("</detail>");
        fault.append("</").append(SOAP_PREFIX).append(":Fault>");
        return new SoapResponse(envelope(fault.toString()), status);
    }

    private static String envelope(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:lh="https://localhive.dev/research/soap">
                  <soapenv:Header/>
                  <soapenv:Body>%s</soapenv:Body>
                </soapenv:Envelope>
                """.formatted(body);
    }

    private void appendData(StringBuilder body, Object data) {
        switch (data) {
            case AdminExecutionGroupDetailResponseDto detail -> appendGroupDetail(body, detail);
            case AdminExecutionGroupActivityResponseDto activity -> appendGroupActivity(body, activity);
            case AdminExecutionGroupArtifactsResponseDto artifacts -> appendGroupArtifacts(body, artifacts);
            default -> throw new IllegalArgumentException("Unsupported SOAP response data.");
        }
    }

    private static void appendGroupDetail(StringBuilder body, AdminExecutionGroupDetailResponseDto detail) {
        appendElement(body, "executionGroupId", detail.executionGroupId());
        appendElement(body, "displayName", detail.displayName());
        appendElement(body, "status", detail.status());
        appendElement(body, "mergeMode", detail.mergeMode());
        appendElement(body, "failurePolicy", detail.failurePolicy());
        appendElement(body, "shardCount", detail.shardCount());
        appendElement(body, "totalExecutions", detail.totalExecutions());
        appendElement(body, "activeExecutions", detail.activeExecutions());
        appendElement(body, "terminalExecutions", detail.terminalExecutions());
        appendChildExecutionCounts(body, detail.childExecutionCounts());
        appendElementIfPresent(body, "createdAt", detail.createdAt());
        appendElementIfPresent(body, "updatedAt", detail.updatedAt());
        appendElementIfPresent(body, "completedAt", detail.completedAt());
        appendElementIfPresent(body, "cancelledAt", detail.cancelledAt());
        appendElementIfPresent(body, "failureCode", detail.failureCode());
        appendElementIfPresent(body, "failureMessage", detail.failureMessage());
        appendObservability(body, detail.observability());
        appendLifecycleActions(body, detail.lifecycleActions());
        appendArtifactSummary(body, detail.artifactSummary());
    }

    private static void appendGroupActivity(StringBuilder body, AdminExecutionGroupActivityResponseDto activity) {
        appendElement(body, "executionGroupId", activity.executionGroupId());
        appendElement(body, "displayName", activity.displayName());
        appendElement(body, "status", activity.status());
        appendElement(body, "mergeMode", activity.mergeMode());
        appendElement(body, "failurePolicy", activity.failurePolicy());
        appendElement(body, "generatedAt", activity.generatedAt());
        body.append("<").append(LOCALHIVE_PREFIX).append(":events>");
        for (AdminExecutionGroupActivityResponseDto.ActivityEventResponseDto event : activity.events()) {
            startElement(body, "event");
            appendElement(body, "type", event.type());
            appendElementIfPresent(body, "occurredAt", event.occurredAt());
            appendElementIfPresent(body, "message", event.message());
            appendElementIfPresent(body, "executionId", event.executionId());
            appendElementIfPresent(body, "groupRole", event.groupRole());
            appendElementIfPresent(body, "shardIndex", event.shardIndex());
            appendElementIfPresent(body, "workerId", event.workerId());
            appendElementIfPresent(body, "workerHostname", event.workerHostname());
            appendElementIfPresent(body, "artifactId", event.artifactId());
            appendElementIfPresent(body, "relativePath", event.relativePath());
            appendElementIfPresent(body, "status", event.status());
            endElement(body, "event");
        }
        body.append("</").append(LOCALHIVE_PREFIX).append(":events>");
    }

    private static void appendGroupArtifacts(StringBuilder body, AdminExecutionGroupArtifactsResponseDto artifacts) {
        appendElement(body, "executionGroupId", artifacts.executionGroupId());
        appendElement(body, "displayName", artifacts.displayName());
        appendElement(body, "status", artifacts.status());
        appendElement(body, "mergeMode", artifacts.mergeMode());
        appendElement(body, "failurePolicy", artifacts.failurePolicy());
        appendArtifactSummary(body, artifacts.artifactSummary());
        appendArtifacts(body, "preferredOutputs", artifacts.preferredOutputs());
        body.append("<").append(LOCALHIVE_PREFIX).append(":shards>");
        for (AdminExecutionGroupArtifactsResponseDto.ShardArtifactsResponseDto shard : artifacts.shards()) {
            startElement(body, "shard");
            appendElementIfPresent(body, "shardIndex", shard.shardIndex());
            appendElementIfPresent(body, "shardCount", shard.shardCount());
            appendElementIfPresent(body, "executionId", shard.executionId());
            appendElementIfPresent(body, "executionStatus", shard.executionStatus());
            appendElementIfPresent(body, "workerId", shard.workerId());
            appendElementIfPresent(body, "workerHostname", shard.workerHostname());
            appendElement(body, "artifactCount", shard.artifactCount());
            appendArtifacts(body, "artifacts", shard.artifacts());
            endElement(body, "shard");
        }
        body.append("</").append(LOCALHIVE_PREFIX).append(":shards>");
        appendMergeArtifacts(body, artifacts.merge());
    }

    private static void appendMergeArtifacts(StringBuilder body,
                                             AdminExecutionGroupArtifactsResponseDto.MergeArtifactsResponseDto merge) {
        startElement(body, "merge");
        appendElement(body, "exists", merge.exists());
        appendElement(body, "mergeExecutionCount", merge.mergeExecutionCount());
        appendElementIfPresent(body, "executionId", merge.executionId());
        appendElementIfPresent(body, "executionStatus", merge.executionStatus());
        appendElementIfPresent(body, "workerId", merge.workerId());
        appendElementIfPresent(body, "workerHostname", merge.workerHostname());
        appendElement(body, "artifactCount", merge.artifactCount());
        appendArtifacts(body, "artifacts", merge.artifacts());
        endElement(body, "merge");
    }

    private static void appendArtifacts(StringBuilder body,
                                        String listName,
                                        List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> artifacts) {
        startElement(body, listName);
        for (AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto artifact : artifacts) {
            startElement(body, "artifact");
            appendElement(body, "artifactId", artifact.artifactId());
            appendElement(body, "executionId", artifact.executionId());
            appendElementIfPresent(body, "groupRole", artifact.groupRole());
            appendElementIfPresent(body, "shardIndex", artifact.shardIndex());
            appendElementIfPresent(body, "relativePath", artifact.relativePath());
            appendElementIfPresent(body, "originalFilename", artifact.originalFilename());
            appendElementIfPresent(body, "contentType", artifact.contentType());
            appendElement(body, "sizeBytes", artifact.sizeBytes());
            appendElementIfPresent(body, "createdAt", artifact.createdAt());
            endElement(body, "artifact");
        }
        endElement(body, listName);
    }

    private static void appendChildExecutionCounts(StringBuilder body, Map<String, Long> counts) {
        startElement(body, "childExecutionCounts");
        counts.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    startElement(body, "count");
                    appendElement(body, "status", entry.getKey());
                    appendElement(body, "value", entry.getValue());
                    endElement(body, "count");
                });
        endElement(body, "childExecutionCounts");
    }

    private static void appendObservability(StringBuilder body,
                                            AdminExecutionGroupDetailResponseDto.ObservabilityResponseDto observability) {
        if (observability == null) {
            return;
        }
        startElement(body, "observability");
        appendElement(body, "terminal", observability.terminal());
        appendElement(body, "cancelInProgress", observability.cancelInProgress());
        appendElement(body, "hasActiveChildren", observability.hasActiveChildren());
        appendElement(body, "hasQueuedChildren", observability.hasQueuedChildren());
        appendElement(body, "canCancel", observability.canCancel());
        appendElement(body, "canReconcile", observability.canReconcile());
        appendRoleCounts(body, "shards", observability.shards());
        appendMergeObservability(body, observability.merge());
        endElement(body, "observability");
    }

    private static void appendRoleCounts(StringBuilder body,
                                         String elementName,
                                         AdminExecutionGroupDetailResponseDto.ChildRoleCountsResponseDto counts) {
        if (counts == null) {
            return;
        }
        startElement(body, elementName);
        appendElement(body, "total", counts.total());
        appendElement(body, "queued", counts.queued());
        appendElement(body, "assigned", counts.assigned());
        appendElement(body, "claimed", counts.claimed());
        appendElement(body, "running", counts.running());
        appendElement(body, "succeeded", counts.succeeded());
        appendElement(body, "failed", counts.failed());
        appendElement(body, "cancelled", counts.cancelled());
        appendElement(body, "expired", counts.expired());
        appendElement(body, "terminal", counts.terminal());
        appendElement(body, "nonTerminal", counts.nonTerminal());
        endElement(body, elementName);
    }

    private static void appendMergeObservability(StringBuilder body,
                                                 AdminExecutionGroupDetailResponseDto.MergeObservabilityResponseDto merge) {
        if (merge == null) {
            return;
        }
        startElement(body, "merge");
        appendElement(body, "exists", merge.exists());
        appendElementIfPresent(body, "executionId", merge.executionId());
        appendElementIfPresent(body, "status", merge.status());
        appendElementIfPresent(body, "workerId", merge.workerId());
        appendElementIfPresent(body, "workerHostname", merge.workerHostname());
        appendElement(body, "total", merge.total());
        appendElement(body, "queued", merge.queued());
        appendElement(body, "assigned", merge.assigned());
        appendElement(body, "claimed", merge.claimed());
        appendElement(body, "running", merge.running());
        appendElement(body, "succeeded", merge.succeeded());
        appendElement(body, "failed", merge.failed());
        appendElement(body, "cancelled", merge.cancelled());
        appendElement(body, "expired", merge.expired());
        appendElement(body, "terminal", merge.terminal());
        appendElement(body, "nonTerminal", merge.nonTerminal());
        endElement(body, "merge");
    }

    private static void appendLifecycleActions(StringBuilder body,
                                               AdminExecutionGroupDetailResponseDto.LifecycleActionsResponseDto actions) {
        if (actions == null) {
            return;
        }
        startElement(body, "lifecycleActions");
        appendLifecycleAction(body, "cancel", actions.cancel());
        appendLifecycleAction(body, "reconcile", actions.reconcile());
        endElement(body, "lifecycleActions");
    }

    private static void appendLifecycleAction(StringBuilder body,
                                              String elementName,
                                              AdminExecutionGroupDetailResponseDto.LifecycleActionResponseDto action) {
        if (action == null) {
            return;
        }
        startElement(body, elementName);
        appendElement(body, "available", action.available());
        appendElementIfPresent(body, "reasonCode", action.reasonCode());
        appendElementIfPresent(body, "reasonMessage", action.reasonMessage());
        appendElementIfPresent(body, "method", action.method());
        appendElementIfPresent(body, "path", action.path());
        appendElement(body, "requiresBody", action.requiresBody());
        appendElement(body, "reasonSupported", action.reasonSupported());
        endElement(body, elementName);
    }

    private static void appendArtifactSummary(StringBuilder body, AdminExecutionGroupArtifactSummaryResponseDto summary) {
        if (summary == null) {
            return;
        }
        startElement(body, "artifactSummary");
        appendElement(body, "totalArtifacts", summary.totalArtifacts());
        appendElement(body, "shardArtifacts", summary.shardArtifacts());
        appendElement(body, "mergeArtifacts", summary.mergeArtifacts());
        appendElement(body, "shardsWithArtifacts", summary.shardsWithArtifacts());
        appendElement(body, "mergeHasArtifacts", summary.mergeHasArtifacts());
        appendElementIfPresent(body, "preferredOutputSource", summary.preferredOutputSource());
        endElement(body, "artifactSummary");
    }

    private static void appendError(StringBuilder body, ResearchSoapErrorReasonCode reasonCode, String message) {
        startElement(body, "error");
        appendElement(body, "reasonCode", reasonCode);
        appendElement(body, "message", message);
        endElement(body, "error");
    }

    private static void appendElement(StringBuilder body, String name, Object value) {
        appendRawElement(body, name, value == null ? "" : value.toString());
    }

    private static void appendElementIfPresent(StringBuilder body, String name, Object value) {
        if (value != null) {
            appendElement(body, name, value);
        }
    }

    private static void appendRawElement(StringBuilder body, String name, String value) {
        startElement(body, name);
        body.append(escape(value));
        endElement(body, name);
    }

    private static void appendPlainElement(StringBuilder body, String name, String value) {
        body.append("<").append(name).append(">");
        body.append(escape(value));
        body.append("</").append(name).append(">");
    }

    private static void startElement(StringBuilder body, String name) {
        body.append("<").append(LOCALHIVE_PREFIX).append(":").append(name).append(">");
    }

    private static void endElement(StringBuilder body, String name) {
        body.append("</").append(LOCALHIVE_PREFIX).append(":").append(name).append(">");
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "SOAP operation failed." : message;
    }

    private static Element firstChildElement(Element parent) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element element) {
                return element;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String expectedLocalName) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element element && expectedLocalName.equals(localName(element))) {
                return element;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private static String localName(Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getNodeName() : localName;
    }

    private static boolean isSoapNamespace(String namespace) {
        return SOAP_11_NAMESPACE.equals(namespace) || SOAP_12_NAMESPACE.equals(namespace);
    }

    private static Map<ResearchOperation, SoapOperation> supportedOperations() {
        EnumMap<ResearchOperation, SoapOperation> operations = new EnumMap<>(ResearchOperation.class);
        operations.put(
                ResearchOperation.GET_GROUP_DETAIL,
                new SoapOperation(
                        ResearchOperation.GET_GROUP_DETAIL,
                        "GetGroupDetailRequest",
                        "GetGroupDetailResponse"
                )
        );
        operations.put(
                ResearchOperation.GET_GROUP_ACTIVITY,
                new SoapOperation(
                        ResearchOperation.GET_GROUP_ACTIVITY,
                        "GetGroupActivityRequest",
                        "GetGroupActivityResponse"
                )
        );
        operations.put(
                ResearchOperation.GET_GROUP_ARTIFACTS,
                new SoapOperation(
                        ResearchOperation.GET_GROUP_ARTIFACTS,
                        "GetGroupArtifactsRequest",
                        "GetGroupArtifactsResponse"
                )
        );
        operations.put(
                ResearchOperation.CANCEL_GROUP,
                new SoapOperation(
                        ResearchOperation.CANCEL_GROUP,
                        "CancelGroupRequest",
                        "CancelGroupResponse"
                )
        );
        operations.put(
                ResearchOperation.RECONCILE_GROUP,
                new SoapOperation(
                        ResearchOperation.RECONCILE_GROUP,
                        "ReconcileGroupRequest",
                        "ReconcileGroupResponse"
                )
        );
        return Map.copyOf(operations);
    }

    private static final ThreadLocal<String> CURRENT_RESPONSE_NAME = new ThreadLocal<>();

    private static String currentResponseName(Throwable ignored) {
        return currentResponseName();
    }

    private static String currentResponseName() {
        String responseName = CURRENT_RESPONSE_NAME.get();
        return responseName == null ? "SoapErrorResponse" : responseName;
    }

    private record SoapRequest(
            ResearchOperation operation,
            String operationName,
            Element operationElement
    ) {
    }

    private record SoapOperation(
            ResearchOperation operation,
            String requestName,
            String responseName
    ) {
    }

    public record SoapResponse(
            String body,
            HttpStatus status
    ) {
    }

    private static final class MalformedSoapException extends RuntimeException {
        private MalformedSoapException(String message) {
            super(message);
        }
    }

    private static final class InvalidPayloadException extends RuntimeException {
        private final String responseName;

        private InvalidPayloadException(String responseName, String message) {
            super(message);
            this.responseName = responseName;
        }

        private String responseName() {
            return responseName;
        }
    }

    private static final class SilentSoapErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
