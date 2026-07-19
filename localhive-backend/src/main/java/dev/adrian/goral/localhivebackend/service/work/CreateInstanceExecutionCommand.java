package dev.adrian.goral.localhivebackend.service.work;

import java.util.UUID;

public record CreateInstanceExecutionCommand(UUID instanceId, String displayName) {

    public CreateInstanceExecutionCommand(UUID instanceId) {
        this(instanceId, null);
    }
}
