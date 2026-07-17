package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionAttemptTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

    @Test
    void shouldCreateRunningAttemptForRunningExecutionAndMatchingAssignment() {
        AssignedExecution assignedExecution = assignedExecution("localhive.attempt");
        assignedExecution.execution().markClaimed(BASE_TIME.plusMinutes(2));
        assignedExecution.execution().markRunning(BASE_TIME.plusMinutes(3));

        ExecutionAttempt attempt = ExecutionAttempt.createRunning(
                assignedExecution.execution(),
                assignedExecution.assignment(),
                1,
                BASE_TIME.plusMinutes(3)
        );

        assertThat(attempt.getExecution()).isSameAs(assignedExecution.execution());
        assertThat(attempt.getAssignment()).isSameAs(assignedExecution.assignment());
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
        assertThat(attempt.getStartedAt()).isEqualTo(BASE_TIME.plusMinutes(3));
        assertThat(attempt.getCompletedAt()).isNull();
        assertThat(attempt.getFailureCode()).isNull();
        assertThat(attempt.getFailureMessage()).isNull();
    }

    @Test
    void shouldRejectAttemptForNonRunningExecution() {
        AssignedExecution assignedExecution = assignedExecution("localhive.attempt-non-running");

        assertThatThrownBy(() -> ExecutionAttempt.createRunning(
                assignedExecution.execution(),
                assignedExecution.assignment(),
                1,
                BASE_TIME.plusMinutes(3)
        )).isInstanceOf(IllegalStateException.class);

        assertThat(assignedExecution.execution().getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED);
    }

    @Test
    void shouldRejectMismatchedAssignmentAndUnsupportedAttemptNumber() {
        AssignedExecution first = runningAssignedExecution("localhive.attempt-first");
        AssignedExecution second = assignedExecution("localhive.attempt-second");

        assertThatThrownBy(() -> ExecutionAttempt.createRunning(
                first.execution(),
                second.assignment(),
                1,
                BASE_TIME.plusMinutes(3)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionAttempt.createRunning(
                first.execution(),
                first.assignment(),
                2,
                BASE_TIME.plusMinutes(3)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMarkAttemptSucceededFailedAndCancelled() {
        ExecutionAttempt succeeded = runningAttempt("localhive.attempt-succeeded");
        succeeded.markSucceeded(BASE_TIME.plusMinutes(4));

        assertThat(succeeded.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        assertThat(succeeded.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
        assertThat(succeeded.getFailureCode()).isNull();
        assertThat(succeeded.getFailureMessage()).isNull();

        ExecutionAttempt failed = runningAttempt("localhive.attempt-failed");
        failed.markFailed("EXECUTOR_ERROR", "Process exited with status 1", BASE_TIME.plusMinutes(4));

        assertThat(failed.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(failed.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
        assertThat(failed.getFailureCode()).isEqualTo("EXECUTOR_ERROR");
        assertThat(failed.getFailureMessage()).isEqualTo("Process exited with status 1");

        ExecutionAttempt cancelled = runningAttempt("localhive.attempt-cancelled");
        cancelled.markCancelled(BASE_TIME.plusMinutes(4));

        assertThat(cancelled.getStatus()).isEqualTo(ExecutionAttemptStatus.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
        assertThat(cancelled.getFailureCode()).isNull();
        assertThat(cancelled.getFailureMessage()).isNull();
    }

    @Test
    void shouldRejectInvalidAttemptTerminalFieldsAndTerminalMutation() {
        ExecutionAttempt failed = runningAttempt("localhive.attempt-invalid-failure");

        assertThatThrownBy(() -> failed.markFailed(" ", null, BASE_TIME.plusMinutes(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> failed.markFailed("EXECUTOR_ERROR", " ", BASE_TIME.plusMinutes(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> failed.markFailed("EXECUTOR_ERROR", null, null))
                .isInstanceOf(NullPointerException.class);

        failed.markFailed("EXECUTOR_ERROR", null, BASE_TIME.plusMinutes(4));

        assertThatThrownBy(() -> failed.markSucceeded(BASE_TIME.plusMinutes(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.markCancelled(BASE_TIME.plusMinutes(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failed.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
    }

    @Test
    void shouldRequireCreationFields() {
        AssignedExecution assignedExecution = runningAssignedExecution("localhive.attempt-required-fields");

        assertThatThrownBy(() -> ExecutionAttempt.createRunning(
                null,
                assignedExecution.assignment(),
                1,
                BASE_TIME.plusMinutes(3)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExecutionAttempt.createRunning(
                assignedExecution.execution(),
                null,
                1,
                BASE_TIME.plusMinutes(3)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExecutionAttempt.createRunning(
                assignedExecution.execution(),
                assignedExecution.assignment(),
                1,
                null
        )).isInstanceOf(NullPointerException.class);
    }

    private static ExecutionAttempt runningAttempt(String logicalIdentifier) {
        AssignedExecution assignedExecution = runningAssignedExecution(logicalIdentifier);
        return ExecutionAttempt.createRunning(
                assignedExecution.execution(),
                assignedExecution.assignment(),
                1,
                BASE_TIME.plusMinutes(3)
        );
    }

    private static AssignedExecution runningAssignedExecution(String logicalIdentifier) {
        AssignedExecution assignedExecution = assignedExecution(logicalIdentifier);
        assignedExecution.execution().markClaimed(BASE_TIME.plusMinutes(2));
        assignedExecution.execution().markRunning(BASE_TIME.plusMinutes(3));
        return assignedExecution;
    }

    private static AssignedExecution assignedExecution(String logicalIdentifier) {
        WorkExecution execution = WorkExecution.createQueued(
                version(logicalIdentifier),
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                BASE_TIME
        );
        execution.markAssigned(BASE_TIME.plusMinutes(1));
        ExecutionAssignment assignment = ExecutionAssignment.create(
                execution,
                eligibleWorker(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );
        return new AssignedExecution(execution, assignment);
    }

    private static WorkDefinitionVersion version(String logicalIdentifier) {
        return WorkDefinitionVersion.createLocal(
                WorkDefinition.createLocal(logicalIdentifier + "-" + UUID.randomUUID(), WorkType.TASK, BASE_TIME),
                1,
                "Definition",
                null,
                "localhive.attempt-executor",
                1,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                "0".repeat(64),
                BASE_TIME,
                UUID.randomUUID()
        );
    }

    private static Worker eligibleWorker() {
        return Worker.builder()
                .hostname("worker-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .build();
    }

    private record AssignedExecution(WorkExecution execution, ExecutionAssignment assignment) {
    }
}
