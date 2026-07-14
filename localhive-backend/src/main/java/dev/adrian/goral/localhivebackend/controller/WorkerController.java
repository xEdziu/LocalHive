package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.dto.*;
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
     * The agent will be created as pending approval and offline until the first authenticated heartbeat.
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
     * Endpoint for an Agent to update only the shared RAM allocation it offers to the swarm.
     */
    @PatchMapping("/{workerId}/allocation")
    public ResponseEntity<WorkerResponseDto> updateAllocation(
            @PathVariable UUID workerId,
            @Valid @RequestBody WorkerAllocationUpdateRequestDto requestDto
    ) {
        log.info("Received allocation update request from worker: {}", workerId);

        try {
            Worker updatedWorker = workerRegistryService.updateWorkerAllocation(workerId, requestDto.getSharedRamMb());
            return ResponseEntity.ok(WorkerResponseDto.fromEntity(updatedWorker));

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Endpoint for an Agent to report hardware changes, including RAM replacements.
     * If total RAM changes, shared RAM must be reported in the same request.
     */
    @PatchMapping("/{workerId}/spec")
    public ResponseEntity<WorkerResponseDto> updateHardwareSpec(
            @PathVariable UUID workerId,
            @Valid @RequestBody WorkerHardwareUpdateRequestDto requestDto
    ) {
        log.info("Received hardware spec update request from worker: {}", workerId);

        try {
            Worker updatedWorker = workerRegistryService.updateWorkerHardwareSpec(workerId, requestDto);
            return ResponseEntity.ok(WorkerResponseDto.fromEntity(updatedWorker));

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Endpoint for an Agent to send its periodic "I am alive" signal with state information.
     * Now includes pauseEnabled and sharedRamMb updates via JSON body.
     * API Key authentication is handled by ApiKeyAuthenticationFilter.
     */
    @PostMapping("/{workerId}/heartbeat")
    public ResponseEntity<?> heartbeat(
            @PathVariable UUID workerId,
            @RequestHeader("X-API-KEY") String apiKey,
            @Valid @RequestBody WorkerHeartbeatRequestDto request
    ) {
        log.info("Received heartbeat from worker: {} - pauseEnabled: {}, sharedRamMb: {}",
                workerId, request.pauseEnabled(), request.sharedRamMb());

        workerRegistryService.handleHeartbeat(workerId, apiKey, request);

        return ResponseEntity.ok(Map.of("status", "success"));
    }
}
