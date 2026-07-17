package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;

public record WorkerExecutionLeaseRenewalResponseDto(LocalDateTime leaseExpiresAt) {
}
