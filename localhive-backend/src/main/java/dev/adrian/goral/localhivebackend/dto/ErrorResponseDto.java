package dev.adrian.goral.localhivebackend.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ErrorResponseDto {
    private String status;
    private String message;
    private LocalDateTime timestamp;
    private Map<String, String> fieldErrors;
}