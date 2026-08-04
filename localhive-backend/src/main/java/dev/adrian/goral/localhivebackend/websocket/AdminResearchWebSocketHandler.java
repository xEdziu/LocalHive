package dev.adrian.goral.localhivebackend.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWebSocketErrorReasonCode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupActivityResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWebSocketEnvelopeDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWebSocketErrorDto;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupActivityStreamService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupControlService;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupQueryService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
public class AdminResearchWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_REQUEST_ID_LENGTH = 100;
    private static final String GROUP_DETAIL_EVENT = "group-detail";
    private static final String ACTIVITY_SNAPSHOT_EVENT = "activity-snapshot";
    private static final String HEARTBEAT_EVENT = "heartbeat";
    private static final EnumSet<ExecutionGroupStatus> TERMINAL_GROUP_STATUSES = EnumSet.of(
            ExecutionGroupStatus.SUCCEEDED,
            ExecutionGroupStatus.PARTIALLY_FAILED,
            ExecutionGroupStatus.FAILED,
            ExecutionGroupStatus.CANCELLED,
            ExecutionGroupStatus.EXPIRED
    );

    private final ObjectMapper objectMapper;
    private final AdminExecutionGroupQueryService queryService;
    private final AdminExecutionGroupControlService controlService;
    private final TaskScheduler scheduler;
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    public AdminResearchWebSocketHandler(
            ObjectMapper objectMapper,
            AdminExecutionGroupQueryService queryService,
            AdminExecutionGroupControlService controlService,
            @Qualifier("adminResearchWebSocketScheduler") TaskScheduler scheduler
    ) {
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.queryService = queryService;
        this.controlService = controlService;
        this.scheduler = scheduler;
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException e) {
            sendError(session, null, null, ResearchWebSocketErrorReasonCode.MALFORMED_MESSAGE, "Malformed JSON message.");
            return;
        }

        if (root == null || !root.isObject()) {
            sendError(session, null, null, ResearchWebSocketErrorReasonCode.MALFORMED_MESSAGE, "Message must be a JSON object.");
            return;
        }

        String requestId;
        try {
            requestId = readRequestId(root);
        } catch (InvalidRequestException e) {
            sendError(session, null, readOptionalOperation(root), e.reasonCode(), e.getMessage());
            return;
        }

        String operationName = readOptionalOperation(root);
        ResearchOperation operation;
        try {
            operation = parseOperation(operationName);
        } catch (InvalidRequestException e) {
            sendError(session, requestId, operationName, e.reasonCode(), e.getMessage());
            return;
        }

        try {
            handleOperation(session, requestId, operation, root.get("payload"));
        } catch (InvalidRequestException e) {
            sendError(session, requestId, operation.name(), e.reasonCode(), e.getMessage());
        } catch (NoSuchElementException e) {
            sendError(session, requestId, operation.name(), ResearchWebSocketErrorReasonCode.GROUP_NOT_FOUND, "Execution group not found.");
        } catch (IllegalStateException e) {
            sendError(session, requestId, operation.name(), ResearchWebSocketErrorReasonCode.OPERATION_CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.warn("Admin research WebSocket operation {} failed: {}", operation, e.toString());
            sendError(session, requestId, operation.name(), ResearchWebSocketErrorReasonCode.INTERNAL_ERROR, "WebSocket operation failed.");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        stopSessionStreams(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        stopSessionStreams(session);
    }

    @PreDestroy
    void stopAllStreams() {
        streams.values()
                .stream()
                .toList()
                .forEach(this::cleanup);
    }

    @EventListener(ContextClosedEvent.class)
    void onContextClosed() {
        stopAllStreams();
    }

    private void handleOperation(WebSocketSession session,
                                 String requestId,
                                 ResearchOperation operation,
                                 JsonNode payload) {
        switch (operation) {
            case GET_GROUP_DETAIL -> sendResponse(
                    session,
                    requestId,
                    operation.name(),
                    groupDetail(readExecutionGroupId(payload))
            );
            case GET_GROUP_ACTIVITY -> sendResponse(
                    session,
                    requestId,
                    operation.name(),
                    groupActivity(readExecutionGroupId(payload))
            );
            case GET_GROUP_ARTIFACTS -> sendResponse(
                    session,
                    requestId,
                    operation.name(),
                    queryService.listGroupArtifacts(readExecutionGroupId(payload))
                            .orElseThrow(() -> new NoSuchElementException("Execution group not found."))
            );
            case STREAM_GROUP_ACTIVITY -> startGroupActivityStream(session, requestId, payload);
            case STOP_STREAM_GROUP_ACTIVITY -> stopGroupActivityStream(session, requestId, payload);
            case CANCEL_GROUP -> cancelGroup(session, requestId, payload);
            case RECONCILE_GROUP -> reconcileGroup(session, requestId, payload);
            case CREATE_SINGLE_EXECUTION,
                 CREATE_EXECUTION_GROUP,
                 GET_EXECUTION_STATUS,
                 DOWNLOAD_ARTIFACT -> throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.OPERATION_NOT_SUPPORTED,
                    "Unsupported WebSocket operation."
            );
        }
    }

    private void cancelGroup(WebSocketSession session, String requestId, JsonNode payload) {
        UUID executionGroupId = readExecutionGroupId(payload);
        controlService.cancelGroup(executionGroupId, readOptionalText(payload, "reason"), LocalDateTime.now());
        sendResponse(session, requestId, ResearchOperation.CANCEL_GROUP.name(), groupDetail(executionGroupId));
    }

    private void reconcileGroup(WebSocketSession session, String requestId, JsonNode payload) {
        UUID executionGroupId = readExecutionGroupId(payload);
        controlService.reconcileGroup(executionGroupId, LocalDateTime.now());
        sendResponse(session, requestId, ResearchOperation.RECONCILE_GROUP.name(), groupDetail(executionGroupId));
    }

    private void startGroupActivityStream(WebSocketSession session, String requestId, JsonNode payload) {
        StreamCommand command = readStreamCommand(payload);
        ActivitySnapshot initialSnapshot = snapshot(command.executionGroupId());
        String streamKey = streamKey(session, requestId);
        StreamState state = new StreamState(session, requestId, command);
        StreamState previous = streams.put(streamKey, state);
        if (previous != null) {
            cleanup(previous);
        }

        state.detailDigest = digest(initialSnapshot.detail());
        state.activityDigest = digest(initialSnapshot.activity());
        state.lastHeartbeatAt = LocalDateTime.now();

        if (!sendStreamEvent(state, GROUP_DETAIL_EVENT, initialSnapshot.detail())) {
            return;
        }
        if (!sendStreamEvent(state, ACTIVITY_SNAPSHOT_EVENT, initialSnapshot.activity())) {
            return;
        }
        if (command.closeOnTerminal() && initialSnapshot.isTerminal()) {
            cleanup(state);
            return;
        }
        if (state.closed) {
            return;
        }

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> pollStream(state),
                Instant.now().plusMillis(command.pollIntervalMs()),
                Duration.ofMillis(command.pollIntervalMs())
        );
        state.future = future;
        if (state.closed) {
            future.cancel(false);
        }
    }

    private void stopGroupActivityStream(WebSocketSession session, String requestId, JsonNode payload) {
        JsonNode validPayload = requireObjectPayload(payload);
        String streamRequestId = readRequiredText(validPayload, "streamRequestId");
        if (streamRequestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    "streamRequestId must be less than or equal to 100 characters."
            );
        }

        StreamState stream = streams.remove(streamKey(session, streamRequestId));
        boolean stopped = stream != null;
        if (stream != null) {
            cleanup(stream);
        }
        sendResponse(
                session,
                requestId,
                ResearchOperation.STOP_STREAM_GROUP_ACTIVITY.name(),
                Map.of("streamRequestId", streamRequestId, "stopped", stopped)
        );
    }

    private void pollStream(StreamState state) {
        if (state.closed || !state.session.isOpen()) {
            cleanup(state);
            return;
        }

        try {
            ActivitySnapshot currentSnapshot = snapshot(state.command.executionGroupId());
            boolean sentSnapshot = false;
            String currentDetailDigest = digest(currentSnapshot.detail());
            if (!currentDetailDigest.equals(state.detailDigest)) {
                state.detailDigest = currentDetailDigest;
                if (!sendStreamEvent(state, GROUP_DETAIL_EVENT, currentSnapshot.detail())) {
                    return;
                }
                sentSnapshot = true;
            }

            String currentActivityDigest = digest(currentSnapshot.activity());
            if (!currentActivityDigest.equals(state.activityDigest)) {
                state.activityDigest = currentActivityDigest;
                if (!sendStreamEvent(state, ACTIVITY_SNAPSHOT_EVENT, currentSnapshot.activity())) {
                    return;
                }
                sentSnapshot = true;
            }

            if (state.command.closeOnTerminal() && currentSnapshot.isTerminal()) {
                cleanup(state);
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            if (!sentSnapshot && heartbeatDue(state.lastHeartbeatAt, now, state.command.heartbeatIntervalMs())) {
                state.lastHeartbeatAt = now;
                sendStreamEvent(
                        state,
                        HEARTBEAT_EVENT,
                        Map.of(
                                "executionGroupId", state.command.executionGroupId(),
                                "generatedAt", now,
                                "status", currentSnapshot.detail().status()
                        )
                );
            }
        } catch (NoSuchElementException e) {
            sendError(
                    state.session,
                    state.requestId,
                    ResearchOperation.STREAM_GROUP_ACTIVITY.name(),
                    ResearchWebSocketErrorReasonCode.GROUP_NOT_FOUND,
                    "Execution group not found."
            );
            cleanup(state);
        } catch (Exception e) {
            log.warn(
                    "Admin research WebSocket stream failed for group {}: {}",
                    state.command.executionGroupId(),
                    e.toString()
            );
            sendError(
                    state.session,
                    state.requestId,
                    ResearchOperation.STREAM_GROUP_ACTIVITY.name(),
                    ResearchWebSocketErrorReasonCode.INTERNAL_ERROR,
                    "WebSocket activity stream failed."
            );
            cleanup(state);
        }
    }

    private ActivitySnapshot snapshot(UUID executionGroupId) {
        return new ActivitySnapshot(
                groupDetail(executionGroupId),
                groupActivity(executionGroupId)
        );
    }

    private AdminExecutionGroupDetailResponseDto groupDetail(UUID executionGroupId) {
        return queryService.getGroup(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }

    private AdminExecutionGroupActivityResponseDto groupActivity(UUID executionGroupId) {
        return queryService.getGroupActivity(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }

    private boolean sendStreamEvent(StreamState state, String eventName, Object data) {
        if (state.closed || !state.session.isOpen()) {
            cleanup(state);
            return false;
        }

        sendEnvelope(
                state.session,
                AdminResearchWebSocketEnvelopeDto.event(
                        state.requestId,
                        ResearchOperation.STREAM_GROUP_ACTIVITY.name(),
                        eventName,
                        data
                )
        );
        state.sentEvents++;
        if (state.command.maxEvents() != null && state.sentEvents >= state.command.maxEvents()) {
            cleanup(state);
            return false;
        }

        return true;
    }

    private void sendResponse(WebSocketSession session, String requestId, String operation, Object data) {
        sendEnvelope(session, AdminResearchWebSocketEnvelopeDto.response(requestId, operation, data));
    }

    private void sendError(WebSocketSession session,
                           String requestId,
                           String operation,
                           ResearchWebSocketErrorReasonCode reasonCode,
                           String message) {
        sendEnvelope(
                session,
                AdminResearchWebSocketEnvelopeDto.error(
                        requestId,
                        operation,
                        new AdminResearchWebSocketErrorDto(reasonCode, message)
                )
        );
    }

    private void sendEnvelope(WebSocketSession session, AdminResearchWebSocketEnvelopeDto envelope) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to send admin research WebSocket message for session {}", session.getId());
            stopSessionStreams(session);
        }
    }

    private void stopSessionStreams(WebSocketSession session) {
        streams.values()
                .stream()
                .filter(stream -> stream.session.getId().equals(session.getId()))
                .toList()
                .forEach(this::cleanup);
    }

    private void cleanup(StreamState state) {
        if (!state.closed) {
            state.closed = true;
            streams.remove(streamKey(state.session, state.requestId), state);
        }
        ScheduledFuture<?> future = state.future;
        if (future != null) {
            future.cancel(false);
        }
    }

    private StreamCommand readStreamCommand(JsonNode payload) {
        JsonNode validPayload = requireObjectPayload(payload);
        int pollIntervalMs = readOptionalInt(
                validPayload,
                "pollIntervalMs",
                AdminExecutionGroupActivityStreamService.DEFAULT_POLL_INTERVAL_MS
        );
        int heartbeatIntervalMs = readOptionalInt(
                validPayload,
                "heartbeatIntervalMs",
                AdminExecutionGroupActivityStreamService.DEFAULT_HEARTBEAT_INTERVAL_MS
        );
        Integer maxEvents = readOptionalNullableInt(validPayload, "maxEvents");
        validateRange(
                pollIntervalMs,
                AdminExecutionGroupActivityStreamService.MIN_POLL_INTERVAL_MS,
                AdminExecutionGroupActivityStreamService.MAX_POLL_INTERVAL_MS,
                "pollIntervalMs"
        );
        validateRange(
                heartbeatIntervalMs,
                AdminExecutionGroupActivityStreamService.MIN_HEARTBEAT_INTERVAL_MS,
                AdminExecutionGroupActivityStreamService.MAX_HEARTBEAT_INTERVAL_MS,
                "heartbeatIntervalMs"
        );
        if (heartbeatIntervalMs < pollIntervalMs) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    "heartbeatIntervalMs must be greater than or equal to pollIntervalMs."
            );
        }
        if (maxEvents != null) {
            validateRange(
                    maxEvents,
                    AdminExecutionGroupActivityStreamService.MIN_MAX_EVENTS,
                    AdminExecutionGroupActivityStreamService.MAX_MAX_EVENTS,
                    "maxEvents"
            );
        }

        return new StreamCommand(
                readExecutionGroupId(validPayload),
                pollIntervalMs,
                heartbeatIntervalMs,
                readOptionalBoolean(validPayload, "closeOnTerminal", false),
                maxEvents
        );
    }

    private UUID readExecutionGroupId(JsonNode payload) {
        JsonNode validPayload = requireObjectPayload(payload);
        String rawValue = readRequiredText(validPayload, "executionGroupId");
        try {
            return UUID.fromString(rawValue);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    "executionGroupId must be a valid UUID."
            );
        }
    }

    private static JsonNode requireObjectPayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    "payload must be a JSON object."
            );
        }
        return payload;
    }

    private static String readRequestId(JsonNode root) {
        JsonNode value = root.get("requestId");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_REQUEST_ID,
                    "requestId is required."
            );
        }
        String requestId = value.asText().trim();
        if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_REQUEST_ID,
                    "requestId must be less than or equal to 100 characters."
            );
        }
        return requestId;
    }

    private static String readOptionalOperation(JsonNode root) {
        JsonNode value = root == null ? null : root.get("operation");
        if (value == null || !value.isTextual()) {
            return null;
        }
        String normalized = value.asText().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static ResearchOperation parseOperation(String operationName) {
        if (operationName == null) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.UNKNOWN_OPERATION,
                    "operation is required."
            );
        }

        try {
            return ResearchOperation.valueOf(operationName);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.UNKNOWN_OPERATION,
                    "Unsupported WebSocket operation."
            );
        }
    }

    private static String readRequiredText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    fieldName + " is required."
            );
        }
        return value.asText().trim();
    }

    private static String readOptionalText(JsonNode object, String fieldName) {
        JsonNode validPayload = requireObjectPayload(object);
        JsonNode value = validPayload.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    fieldName + " must be a string."
            );
        }
        return value.asText();
    }

    private static int readOptionalInt(JsonNode object, String fieldName, int defaultValue) {
        Integer value = readOptionalNullableInt(object, fieldName);
        return value == null ? defaultValue : value;
    }

    private static Integer readOptionalNullableInt(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    fieldName + " must be a whole number."
            );
        }
        return value.intValue();
    }

    private static boolean readOptionalBoolean(JsonNode object, String fieldName, boolean defaultValue) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    fieldName + " must be true or false."
            );
        }
        return value.booleanValue();
    }

    private static void validateRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new InvalidRequestException(
                    ResearchWebSocketErrorReasonCode.INVALID_PAYLOAD,
                    fieldName + " must be between " + min + " and " + max + "."
            );
        }
    }

    private String digest(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson(objectMapper.valueToTree(value)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Activity stream digest cannot be computed.", e);
        }
    }

    private static String canonicalJson(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isObject()) {
            StringBuilder builder = new StringBuilder("{");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            fields.forEachRemaining(field -> {
                if (!"generatedAt".equals(field.getKey())) {
                    if (builder.length() > 1) {
                        builder.append(',');
                    }
                    builder.append(field.getKey()).append('=').append(canonicalJson(field.getValue()));
                }
            });
            return builder.append('}').toString();
        }
        if (value.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (JsonNode element : value) {
                if (builder.length() > 1) {
                    builder.append(',');
                }
                builder.append(canonicalJson(element));
            }
            return builder.append(']').toString();
        }

        return value.asText(value.toString());
    }

    private static boolean heartbeatDue(LocalDateTime lastHeartbeatAt, LocalDateTime now, int heartbeatIntervalMs) {
        return !now.isBefore(lastHeartbeatAt.plusNanos(heartbeatIntervalMs * 1_000_000L));
    }

    private static String streamKey(WebSocketSession session, String requestId) {
        return session.getId() + ":" + requestId;
    }

    private record StreamCommand(
            UUID executionGroupId,
            int pollIntervalMs,
            int heartbeatIntervalMs,
            boolean closeOnTerminal,
            Integer maxEvents
    ) {
    }

    private record ActivitySnapshot(
            AdminExecutionGroupDetailResponseDto detail,
            AdminExecutionGroupActivityResponseDto activity
    ) {

        private boolean isTerminal() {
            return TERMINAL_GROUP_STATUSES.contains(ExecutionGroupStatus.valueOf(detail.status()));
        }
    }

    private static final class StreamState {
        private final WebSocketSession session;
        private final String requestId;
        private final StreamCommand command;
        private volatile ScheduledFuture<?> future;
        private volatile boolean closed;
        private volatile int sentEvents;
        private volatile String detailDigest;
        private volatile String activityDigest;
        private volatile LocalDateTime lastHeartbeatAt;

        private StreamState(WebSocketSession session, String requestId, StreamCommand command) {
            this.session = session;
            this.requestId = requestId;
            this.command = command;
        }
    }

    private static final class InvalidRequestException extends RuntimeException {
        private final ResearchWebSocketErrorReasonCode reasonCode;

        private InvalidRequestException(ResearchWebSocketErrorReasonCode reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        private ResearchWebSocketErrorReasonCode reasonCode() {
            return reasonCode;
        }
    }
}
