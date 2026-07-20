package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminCreateExecutionResponseDto(
        UUID executionId,
        String displayName,
        String status,
        UUID workDefinitionVersionId,
        String workDefinitionLogicalId,
        int workDefinitionVersion,
        String executorId,
        int executorContractVersion,
        AssignmentDto assignment,
        LocalDateTime createdAt
) {

    public static AdminCreateExecutionResponseDto from(ExecutionAssignment assignment) {
        WorkExecution execution = assignment.getExecution();
        WorkDefinitionVersion definitionVersion = execution.getDefinitionVersion();
        return new AdminCreateExecutionResponseDto(
                execution.getId(),
                execution.getDisplayNameSnapshot(),
                execution.getStatus().name(),
                definitionVersion.getId(),
                definitionVersion.getDefinition().getLogicalIdentifier(),
                definitionVersion.getVersionNumber(),
                definitionVersion.getExecutorId(),
                definitionVersion.getExecutorContractVersion(),
                new AssignmentDto(
                        assignment.getWorker().getId(),
                        assignment.getWorker().getHostname(),
                        assignment.getAssignmentMode().name(),
                        assignment.getAssignedAt()
                ),
                execution.getCreatedAt()
        );
    }

    public record AssignmentDto(
            UUID workerId,
            String workerHostname,
            String mode,
            LocalDateTime assignedAt
    ) {
    }
}
