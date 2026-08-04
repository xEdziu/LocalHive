package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupActivityResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupStreamCompleteDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupStreamErrorDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupStreamHeartbeatDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AdminExecutionGroupActivityStreamService {

    public static final int DEFAULT_POLL_INTERVAL_MS = 2_000;
    public static final int MIN_POLL_INTERVAL_MS = 500;
    public static final int MAX_POLL_INTERVAL_MS = 10_000;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000;
    public static final int MIN_HEARTBEAT_INTERVAL_MS = 1_000;
    public static final int MAX_HEARTBEAT_INTERVAL_MS = 60_000;
    public static final int MIN_MAX_EVENTS = 1;
    public static final int MAX_MAX_EVENTS = 1_000;

    private static final long SSE_TIMEOUT_MS = 0L;
    private static final String GROUP_DETAIL_EVENT = "group-detail";
    private static final String ACTIVITY_SNAPSHOT_EVENT = "activity-snapshot";
    private static final String HEARTBEAT_EVENT = "heartbeat";
    private static final String STREAM_COMPLETE_EVENT = "stream-complete";
    private static final String STREAM_ERROR_EVENT = "stream-error";
    private static final String MAX_EVENTS_REACHED_REASON = "MAX_EVENTS_REACHED";
    private static final String TERMINAL_GROUP_REACHED_REASON = "TERMINAL_GROUP_REACHED";
    private static final EnumSet<ExecutionGroupStatus> TERMINAL_GROUP_STATUSES = EnumSet.of(
            ExecutionGroupStatus.SUCCEEDED,
            ExecutionGroupStatus.PARTIALLY_FAILED,
            ExecutionGroupStatus.FAILED,
            ExecutionGroupStatus.CANCELLED,
            ExecutionGroupStatus.EXPIRED
    );

    private final AdminExecutionGroupQueryService queryService;
    private final TaskExecutor streamExecutor;

    public AdminExecutionGroupActivityStreamService(
            AdminExecutionGroupQueryService queryService,
            @Qualifier("adminExecutionGroupSseExecutor") TaskExecutor streamExecutor
    ) {
        this.queryService = queryService;
        this.streamExecutor = streamExecutor;
    }

    public SseEmitter streamGroupActivity(AdminExecutionGroupActivityStreamCommand command) {
        AdminExecutionGroupActivityStreamCommand validCommand = requireValidCommand(command);
        ActivitySnapshot initialSnapshot = snapshot(validCommand.executionGroupId());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(error -> closed.set(true));
        streamExecutor.execute(() -> runStream(emitter, validCommand, initialSnapshot, closed));
        return emitter;
    }

    private void runStream(SseEmitter emitter,
                           AdminExecutionGroupActivityStreamCommand command,
                           ActivitySnapshot initialSnapshot,
                           AtomicBoolean closed) {
        StreamState state = new StreamState(command.maxEvents());
        ActivitySnapshot lastSnapshot = initialSnapshot;
        LocalDateTime lastHeartbeatAt = LocalDateTime.now();
        try {
            if (!sendEvent(emitter, state, GROUP_DETAIL_EVENT, initialSnapshot.detail())) {
                return;
            }
            if (!sendEvent(emitter, state, ACTIVITY_SNAPSHOT_EVENT, initialSnapshot.activity())) {
                return;
            }
            if (command.closeOnTerminal() && initialSnapshot.isTerminal()) {
                sendComplete(emitter, state, command.executionGroupId(), TERMINAL_GROUP_REACHED_REASON);
                return;
            }

            while (!closed.get()) {
                sleep(command.pollIntervalMs(), closed);
                if (closed.get()) {
                    return;
                }
                if (shouldReserveMaxEventsComplete(state)) {
                    sendComplete(emitter, state, command.executionGroupId(), MAX_EVENTS_REACHED_REASON);
                    return;
                }

                ActivitySnapshot currentSnapshot = snapshot(command.executionGroupId());
                boolean sentSnapshot = false;
                if (!currentSnapshot.detailDigest().equals(lastSnapshot.detailDigest())) {
                    if (!sendEvent(emitter, state, GROUP_DETAIL_EVENT, currentSnapshot.detail())) {
                        return;
                    }
                    sentSnapshot = true;
                }
                if (!currentSnapshot.activityDigest().equals(lastSnapshot.activityDigest())) {
                    if (!sendEvent(emitter, state, ACTIVITY_SNAPSHOT_EVENT, currentSnapshot.activity())) {
                        return;
                    }
                    sentSnapshot = true;
                }
                lastSnapshot = currentSnapshot;

                if (command.closeOnTerminal() && currentSnapshot.isTerminal()) {
                    sendComplete(emitter, state, command.executionGroupId(), TERMINAL_GROUP_REACHED_REASON);
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                if (!sentSnapshot && heartbeatDue(lastHeartbeatAt, now, command.heartbeatIntervalMs())) {
                    if (!sendEvent(
                            emitter,
                            state,
                            HEARTBEAT_EVENT,
                            new AdminExecutionGroupStreamHeartbeatDto(
                                    command.executionGroupId(),
                                    now,
                                    currentSnapshot.detail().status()
                            )
                    )) {
                        return;
                    }
                    lastHeartbeatAt = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            complete(emitter, closed);
        } catch (NoSuchElementException e) {
            sendErrorAndComplete(emitter, state, command.executionGroupId(), "Execution group no longer exists.", closed);
        } catch (Exception e) {
            log.warn(
                    "Admin execution group activity stream failed for group {}: {}",
                    command.executionGroupId(),
                    e.toString()
            );
            sendErrorAndComplete(emitter, state, command.executionGroupId(), "Activity stream failed.", closed);
        }
    }

    private ActivitySnapshot snapshot(UUID executionGroupId) {
        AdminExecutionGroupDetailResponseDto detail = queryService.getGroup(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
        AdminExecutionGroupActivityResponseDto activity = queryService.getGroupActivity(executionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
        return new ActivitySnapshot(
                detail,
                activity,
                digest(detail),
                digest(activity)
        );
    }

    private static AdminExecutionGroupActivityStreamCommand requireValidCommand(
            AdminExecutionGroupActivityStreamCommand command
    ) {
        AdminExecutionGroupActivityStreamCommand validCommand = Objects.requireNonNull(
                command,
                "command must not be null."
        );
        Objects.requireNonNull(validCommand.executionGroupId(), "executionGroupId must not be null.");
        requireRange(
                validCommand.pollIntervalMs(),
                MIN_POLL_INTERVAL_MS,
                MAX_POLL_INTERVAL_MS,
                "pollIntervalMs"
        );
        requireRange(
                validCommand.heartbeatIntervalMs(),
                MIN_HEARTBEAT_INTERVAL_MS,
                MAX_HEARTBEAT_INTERVAL_MS,
                "heartbeatIntervalMs"
        );
        if (validCommand.heartbeatIntervalMs() < validCommand.pollIntervalMs()) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be greater than or equal to pollIntervalMs.");
        }
        if (validCommand.maxEvents() != null) {
            requireRange(validCommand.maxEvents(), MIN_MAX_EVENTS, MAX_MAX_EVENTS, "maxEvents");
        }

        return validCommand;
    }

    private static void requireRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max + ".");
        }
    }

    private boolean sendEvent(SseEmitter emitter, StreamState state, String eventName, Object data) {
        if (state.maxReached()) {
            complete(emitter, null);
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
            state.recordSent();
            if (state.maxReached()) {
                complete(emitter, null);
                return false;
            }

            return true;
        } catch (IOException | IllegalStateException e) {
            complete(emitter, null);
            return false;
        }
    }

    private void sendComplete(SseEmitter emitter, StreamState state, UUID executionGroupId, String reason) {
        if (!state.maxReached()) {
            try {
                emitter.send(SseEmitter.event()
                        .name(STREAM_COMPLETE_EVENT)
                        .data(new AdminExecutionGroupStreamCompleteDto(
                                executionGroupId,
                                LocalDateTime.now(),
                                reason
                        )));
                state.recordSent();
            } catch (IOException | IllegalStateException ignored) {
                // Client disconnects are expected for SSE streams.
            }
        }
        complete(emitter, null);
    }

    private void sendErrorAndComplete(SseEmitter emitter,
                                      StreamState state,
                                      UUID executionGroupId,
                                      String message,
                                      AtomicBoolean closed) {
        if (!state.maxReached() && !closed.get()) {
            try {
                emitter.send(SseEmitter.event()
                        .name(STREAM_ERROR_EVENT)
                        .data(new AdminExecutionGroupStreamErrorDto(
                                executionGroupId,
                                LocalDateTime.now(),
                                message
                        )));
                state.recordSent();
            } catch (IOException | IllegalStateException ignored) {
                // Client disconnects are expected for SSE streams.
            }
        }
        complete(emitter, closed);
    }

    private static void complete(SseEmitter emitter, AtomicBoolean closed) {
        if (closed != null) {
            closed.set(true);
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Already completed by the client or container.
        }
    }

    private static void sleep(int pollIntervalMs, AtomicBoolean closed) throws InterruptedException {
        if (!closed.get()) {
            Thread.sleep(pollIntervalMs);
        }
    }

    private static boolean heartbeatDue(LocalDateTime lastHeartbeatAt, LocalDateTime now, int heartbeatIntervalMs) {
        return !now.isBefore(lastHeartbeatAt.plusNanos(heartbeatIntervalMs * 1_000_000L));
    }

    private static boolean shouldReserveMaxEventsComplete(StreamState state) {
        return state.remainingEvents() == 1;
    }

    private String digest(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalValue(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Activity stream digest cannot be computed.", e);
        }
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof LocalDateTime) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> canonicalValue(entry.getKey())))
                    .map(entry -> canonicalValue(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(AdminExecutionGroupActivityStreamService::canonicalValue)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            return Arrays.stream(toObjectArray(value, length))
                    .map(AdminExecutionGroupActivityStreamService::canonicalValue)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .filter(component -> !"generatedAt".equals(component.getName()))
                    .map(component -> component.getName() + "=" + canonicalValue(readRecordComponent(component, value)))
                    .collect(Collectors.joining(",", type.getName() + "[", "]"));
        }

        return value.toString();
    }

    private static Object[] toObjectArray(Object array, int length) {
        Object[] values = new Object[length];
        for (int index = 0; index < length; index++) {
            values[index] = Array.get(array, index);
        }
        return values;
    }

    private static Object readRecordComponent(RecordComponent component, Object value) {
        try {
            return component.getAccessor().invoke(value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Activity stream digest cannot read record component.", e);
        }
    }

    private record ActivitySnapshot(
            AdminExecutionGroupDetailResponseDto detail,
            AdminExecutionGroupActivityResponseDto activity,
            String detailDigest,
            String activityDigest
    ) {

        private boolean isTerminal() {
            return TERMINAL_GROUP_STATUSES.contains(ExecutionGroupStatus.valueOf(detail.status()));
        }
    }

    private static final class StreamState {
        private final Integer maxEvents;
        private int sentEvents;

        private StreamState(Integer maxEvents) {
            this.maxEvents = maxEvents;
        }

        private void recordSent() {
            sentEvents++;
        }

        private boolean maxReached() {
            return maxEvents != null && sentEvents >= maxEvents;
        }

        private int remainingEvents() {
            return maxEvents == null ? Integer.MAX_VALUE : maxEvents - sentEvents;
        }
    }
}
