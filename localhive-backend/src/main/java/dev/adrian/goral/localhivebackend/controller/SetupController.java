package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.dto.SetupRequestDto;
import dev.adrian.goral.localhivebackend.service.SetupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;

    /**
     * Endpoint for the React frontend to submit the First-Time Config form.
     */
    @PostMapping
    public ResponseEntity<?> completeSetup(@Valid @RequestBody SetupRequestDto requestDto) {
        log.info("Received request to complete First-Time Setup for user: {}", requestDto.getUsername());

        try {
            setupService.completeFirstTimeSetup(
                    requestDto.getUsername(),
                    requestDto.getPassword(),
                    requestDto.getDataRoot()
            );

            // Return a standard JSON success response
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "System configured successfully. You can now log in."
            ));

        } catch (IllegalArgumentException e) {
            log.warn("Setup request validation failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // Catches the case where someone tries to run setup on an already configured system
            log.warn("Setup attempt failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Helper endpoint for the frontend to check if it should display the Setup Wizard or the Login Screen.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getSetupStatus() {
        boolean requiresSetup = setupService.isSetupRequired();
        return ResponseEntity.ok(Map.of(
                "requiresSetup", requiresSetup
        ));
    }
}
