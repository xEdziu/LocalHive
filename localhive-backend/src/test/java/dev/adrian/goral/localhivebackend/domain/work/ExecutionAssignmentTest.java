package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionAssignmentTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

    @Test
    void shouldCreateAssignmentForAssignedExecution() {
        WorkExecution execution = queuedExecution();
        execution.markAssigned(BASE_TIME.plusMinutes(1));
        Worker worker = eligibleWorker();

        ExecutionAssignment assignment = ExecutionAssignment.create(
                execution,
                worker,
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );

        assertThat(assignment.getExecution()).isSameAs(execution);
        assertThat(assignment.getWorker()).isSameAs(worker);
        assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.AUTO);
        assertThat(assignment.getAssignedAt()).isEqualTo(BASE_TIME.plusMinutes(1));
    }

    @Test
    void shouldRejectAssignmentForNonAssignedExecution() {
        WorkExecution execution = queuedExecution();

        assertThatThrownBy(() -> ExecutionAssignment.create(
                execution,
                eligibleWorker(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(IllegalStateException.class);

        assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
    }

    @Test
    void shouldRequireAssignmentFields() {
        WorkExecution execution = queuedExecution();
        execution.markAssigned(BASE_TIME.plusMinutes(1));
        Worker worker = eligibleWorker();

        assertThatThrownBy(() -> ExecutionAssignment.create(
                null,
                worker,
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExecutionAssignment.create(
                execution,
                null,
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExecutionAssignment.create(
                execution,
                worker,
                null,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExecutionAssignment.create(
                execution,
                worker,
                ExecutionAssignmentMode.AUTO,
                null
        )).isInstanceOf(NullPointerException.class);
    }

    private static WorkExecution queuedExecution() {
        return WorkExecution.createQueued(
                version("localhive.assignment"),
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                BASE_TIME
        );
    }

    private static WorkDefinitionVersion version(String logicalIdentifier) {
        return WorkDefinitionVersion.createLocal(
                WorkDefinition.createLocal(logicalIdentifier + "-" + UUID.randomUUID(), WorkType.TASK, BASE_TIME),
                1,
                "Definition",
                null,
                "localhive.assignment-executor",
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
}
