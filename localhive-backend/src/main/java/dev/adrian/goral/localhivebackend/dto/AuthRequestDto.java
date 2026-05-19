package dev.adrian.goral.localhivebackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthRequestDto {

    @NotBlank(message = "Username cannot be blank")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(
            regexp = "^[A-Za-z0-9._-]+$",
            message = "Username can contain only letters, digits, dot, underscore and hyphen"
    )
    private String username;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 1, max = 128, message = "Password must be at most 128 characters")
    private String password;
}