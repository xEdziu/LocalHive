package dev.adrian.goral.localhivebackend.service.work;

import java.util.UUID;

public record AdminExecutionGroupActivityStreamCommand(
        UUID executionGroupId,
        int pollIntervalMs,
        int heartbeatIntervalMs,
        boolean closeOnTerminal,
        Integer maxEvents
) {
}
