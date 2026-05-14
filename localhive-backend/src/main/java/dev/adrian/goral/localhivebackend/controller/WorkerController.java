package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.dto.WorkerRegistrationRequestDto;
import dev.adrian.goral.localhivebackend.service.WorkerRegistryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerRegistryService workerRegistryService;

    /**
     * Endpoint for an Agent to request joining the cluster.
     * The agent will be created with a PENDING status.
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerWorker(@Valid @RequestBody WorkerRegistrationRequestDto requestDto) {
        log.info("Received registration request from agent: {} ({})",
                requestDto.getHostname(), requestDto.getIpAddress());

        try {
            Worker newWorker = workerRegistryService.registerNewWorker(
                    requestDto.getHostname(),
                    requestDto.getIpAddress(),
                    requestDto.getOsType(),
                    requestDto.getTotalRamMb(),
                    requestDto.getSharedRamMb(),
                    requestDto.getCpuCores(),
                    requestDto.getGpuName()
            );

            // We return the UUID to the Agent so it knows its identity for future Heartbeats
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "status", "success",
                    "message", "Agent registered successfully. Waiting for Admin approval.",
                    "workerId", newWorker.getId()
            ));

        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Endpoint for an Agent to send its periodic "I am alive" signal.
     */
    @PostMapping("/{workerId}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable UUID workerId) {
        // TODO: In the Security step, we will require an API-Key header here.
        workerRegistryService.recordHeartbeat(workerId);

        return ResponseEntity.ok(Map.of("status", "success"));
    }
}