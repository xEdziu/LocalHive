package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkExecutionTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

    @Test
    void shouldCreateQueuedExecutionWithSnapshotsAndDefensiveCopy() {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("threads", 2);
        WorkExecution execution = WorkExecution.createQueued(
                version("localhive.execution-create"),
                null,
                snapshot,
                ResourceRequest.of(1024, 1, false),
                BASE_TIME
        );

        snapshot.put("threads", 99);
        ObjectNode returnedSnapshot = (ObjectNode) execution.getResolvedConfigurationSnapshot();
        returnedSnapshot.put("threads", 100);

        assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
        assertThat(execution.getCreatedAt()).isEqualTo(BASE_TIME);
        assertThat(execution.getQueuedAt()).isEqualTo(BASE_TIME);
        assertThat(execution.getAssignedAt()).isNull();
        assertThat(execution.getInstance()).isNull();
        assertThat(execution.getDisplayNameSnapshot()).isEqualTo("Executor");
        assertThat(execution.getResolvedConfigurationSnapshot().get("threads").intValue()).isEqualTo(2);
        assertThat(execution.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(1024, 1, false));
        assertThat(execution.getFailureCode()).isNull();
        assertThat(execution.getFailureMessage()).isNull();
    }

    @Test
    void shouldStoreTrimmedDisplayNameSnapshot() {
        WorkExecution execution = WorkExecution.createQueued(
                version("localhive.execution-display"),
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                "  Custom display name  ",
                BASE_TIME
        );

        assertThat(execution.getDisplayNameSnapshot()).isEqualTo("Custom display name");
    }

    @Test
    void shouldFallbackBlankDisplayNameSnapshotAndRejectTooLongDisplayName() {
        WorkDefinitionVersion version = version("localhive.execution-display-fallback");

        WorkExecution execution = WorkExecution.createQueued(
                version,
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                "   ",
                BASE_TIME
        );

        assertThat(execution.getDisplayNameSnapshot()).isEqualTo("Executor");
        assertThatThrownBy(() -> WorkExecution.createQueued(
                version,
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                "x".repeat(WorkExecutionDisplayName.MAX_LENGTH + 1),
                BASE_TIME
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRunAllowedSuccessTransitionChain() {
        WorkExecution execution = queuedExecution();

        execution.markAssigned(BASE_TIME.plusMinutes(1));
        execution.markClaimed(BASE_TIME.plusMinutes(2));
        execution.markRunning(BASE_TIME.plusMinutes(3));
        execution.markSucceeded(BASE_TIME.plusMinutes(4));

        assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
        assertThat(execution.getAssignedAt()).isEqualTo(BASE_TIME.plusMinutes(1));
        assertThat(execution.getClaimedAt()).isEqualTo(BASE_TIME.plusMinutes(2));
        assertThat(execution.getStartedAt()).isEqualTo(BASE_TIME.plusMinutes(3));
        assertThat(execution.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
        assertThat(execution.getCancelledAt()).isNull();
        assertThat(execution.getExpiredAt()).isNull();
        assertThat(execution.getFailureCode()).isNull();
    }

    @Test
    void shouldFailFromRunningWithFailureFields() {
        WorkExecution execution = executionAt(WorkExecutionStatus.RUNNING);

        execution.markFailed("EXECUTOR_ERROR", null, BASE_TIME.plusMinutes(4));

        assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.FAILED);
        assertThat(execution.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
        assertThat(execution.getFailureCode()).isEqualTo("EXECUTOR_ERROR");
        assertThat(execution.getFailureMessage()).isNull();
    }

    @Test
    void shouldCancelActiveStatesWithoutCompleting() {
        for (WorkExecutionStatus status : List.of(
                WorkExecutionStatus.QUEUED,
                WorkExecutionStatus.ASSIGNED,
                WorkExecutionStatus.CLAIMED,
                WorkExecutionStatus.RUNNING
        )) {
            WorkExecution execution = executionAt(status);

            execution.cancel(BASE_TIME.plusMinutes(10));

            assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED);
            assertThat(execution.getCancelledAt()).isEqualTo(BASE_TIME.plusMinutes(10));
            assertThat(execution.getCompletedAt()).isNull();
        }
    }

    @Test
    void shouldExpireAssignedAndClaimedWithoutCompleting() {
        for (WorkExecutionStatus status : List.of(WorkExecutionStatus.ASSIGNED, WorkExecutionStatus.CLAIMED)) {
            WorkExecution execution = executionAt(status);

            execution.expire(BASE_TIME.plusMinutes(10));

            assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.EXPIRED);
            assertThat(execution.getExpiredAt()).isEqualTo(BASE_TIME.plusMinutes(10));
            assertThat(execution.getCompletedAt()).isNull();
        }
    }

    @Test
    void shouldRejectForbiddenTransitionsWithoutMutation() {
        assertForbidden(executionAt(WorkExecutionStatus.QUEUED), e -> e.markRunning(BASE_TIME), WorkExecutionStatus.QUEUED);
        assertForbidden(executionAt(WorkExecutionStatus.QUEUED), e -> e.markSucceeded(BASE_TIME), WorkExecutionStatus.QUEUED);
        assertForbidden(executionAt(WorkExecutionStatus.ASSIGNED), e -> e.markRunning(BASE_TIME), WorkExecutionStatus.ASSIGNED);
        assertForbidden(executionAt(WorkExecutionStatus.CLAIMED), e -> e.markSucceeded(BASE_TIME), WorkExecutionStatus.CLAIMED);
        assertForbidden(executionAt(WorkExecutionStatus.RUNNING), e -> e.markClaimed(BASE_TIME), WorkExecutionStatus.RUNNING);
        assertForbidden(executionAt(WorkExecutionStatus.RUNNING), e -> e.expire(BASE_TIME), WorkExecutionStatus.RUNNING);
        assertForbidden(executionAt(WorkExecutionStatus.SUCCEEDED), e -> e.markFailed("FAIL", "failed", BASE_TIME), WorkExecutionStatus.SUCCEEDED);
        assertForbidden(executionAt(WorkExecutionStatus.FAILED), e -> e.markRunning(BASE_TIME), WorkExecutionStatus.FAILED);
        assertForbidden(executionAt(WorkExecutionStatus.CANCELLED), e -> e.markRunning(BASE_TIME), WorkExecutionStatus.CANCELLED);
        assertForbidden(executionAt(WorkExecutionStatus.EXPIRED), e -> e.markRunning(BASE_TIME), WorkExecutionStatus.EXPIRED);
    }

    @Test
    void terminalStatesShouldRejectAllTransitions() {
        for (WorkExecutionStatus terminalStatus : List.of(
                WorkExecutionStatus.SUCCEEDED,
                WorkExecutionStatus.FAILED,
                WorkExecutionStatus.CANCELLED,
                WorkExecutionStatus.EXPIRED
        )) {
            WorkExecution execution = executionAt(terminalStatus);

            assertThatThrownBy(() -> execution.markAssigned(BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.markClaimed(BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.markRunning(BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.markSucceeded(BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.markFailed("FAIL", "failed", BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.cancel(BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.expire(BASE_TIME)).isInstanceOf(IllegalStateException.class);
            assertThat(execution.getStatus()).isEqualTo(terminalStatus);
        }
    }

    @Test
    void shouldRequireTransitionTimestampsAndValidFailureFields() {
        assertThatThrownBy(() -> queuedExecution().markAssigned(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executionAt(WorkExecutionStatus.RUNNING).markFailed(" ", null, BASE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executionAt(WorkExecutionStatus.RUNNING).markFailed("FAIL", " ", BASE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executionAt(WorkExecutionStatus.RUNNING).markFailed("FAIL", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidSnapshotsAndMismatchedInstanceDefinitionVersion() {
        WorkDefinitionVersion version = version("localhive.execution-invalid");
        WorkInstance otherInstance = WorkInstance.create(
                version("localhive.execution-other"),
                "Other",
                BASE_TIME
        );

        assertThatThrownBy(() -> WorkExecution.createQueued(
                version,
                null,
                JsonNodeFactory.instance.arrayNode(),
                ResourceRequest.zero(),
                BASE_TIME
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> WorkExecution.createQueued(
                version,
                otherInstance,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                BASE_TIME
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertForbidden(WorkExecution execution,
                                        ExecutionTransition transition,
                                        WorkExecutionStatus expectedStatus) {
        assertThatThrownBy(() -> transition.apply(execution)).isInstanceOf(IllegalStateException.class);
        assertThat(execution.getStatus()).isEqualTo(expectedStatus);
    }

    private static WorkExecution queuedExecution() {
        return WorkExecution.createQueued(
                version("localhive.execution"),
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                BASE_TIME
        );
    }

    private static WorkExecution executionAt(WorkExecutionStatus status) {
        WorkExecution execution = queuedExecution();
        switch (status) {
            case QUEUED -> {
            }
            case ASSIGNED -> execution.markAssigned(BASE_TIME.plusMinutes(1));
            case CLAIMED -> {
                execution.markAssigned(BASE_TIME.plusMinutes(1));
                execution.markClaimed(BASE_TIME.plusMinutes(2));
            }
            case RUNNING -> {
                execution.markAssigned(BASE_TIME.plusMinutes(1));
                execution.markClaimed(BASE_TIME.plusMinutes(2));
                execution.markRunning(BASE_TIME.plusMinutes(3));
            }
            case SUCCEEDED -> {
                execution.markAssigned(BASE_TIME.plusMinutes(1));
                execution.markClaimed(BASE_TIME.plusMinutes(2));
                execution.markRunning(BASE_TIME.plusMinutes(3));
                execution.markSucceeded(BASE_TIME.plusMinutes(4));
            }
            case FAILED -> {
                execution.markAssigned(BASE_TIME.plusMinutes(1));
                execution.markClaimed(BASE_TIME.plusMinutes(2));
                execution.markRunning(BASE_TIME.plusMinutes(3));
                execution.markFailed("FAIL", "failed", BASE_TIME.plusMinutes(4));
            }
            case CANCELLED -> execution.cancel(BASE_TIME.plusMinutes(1));
            case EXPIRED -> {
                execution.markAssigned(BASE_TIME.plusMinutes(1));
                execution.expire(BASE_TIME.plusMinutes(2));
            }
        }
        return execution;
    }

    private static WorkDefinitionVersion version(String logicalIdentifier) {
        return WorkDefinitionVersion.createLocal(
                WorkDefinition.createLocal(logicalIdentifier, WorkType.TASK, BASE_TIME),
                1,
                "Definition",
                null,
                "localhive.executor",
                1,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                "0".repeat(64),
                BASE_TIME,
                UUID.randomUUID()
        );
    }

    @FunctionalInterface
    private interface ExecutionTransition {
        void apply(WorkExecution execution);
    }
}
