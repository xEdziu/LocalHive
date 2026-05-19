package dev.adrian.goral.localhivebackend.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AuthResponseDto {
    private String token;
    private String username;
    private String role;
    private UUID userId;
}