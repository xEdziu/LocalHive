package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;

public record ClaimedExecution(
        WorkExecution execution,
        ExecutionAssignment assignment,
        String rawLeaseToken
) {
}
