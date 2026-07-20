package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.dto.AdminWorkerDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.WorkerResponseDto;
import dev.adrian.goral.localhivebackend.service.WorkerRegistryService;
import dev.adrian.goral.localhivebackend.service.work.AdminWorkerDetailQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/workers")
@RequiredArgsConstructor
public class AdminWorkerController {

    private final WorkerRegistryService workerRegistryService;
    private final AdminWorkerDetailQueryService workerDetailQueryService;

    /**
     * Retrieves all workers for the Admin Dashboard.
     * @return a list of WorkerResponseDto objects representing all registered workers in the cluster.
     */
    @GetMapping
    public ResponseEntity<List<WorkerResponseDto>> getAllWorkers() {
        log.info("ADMIN: Requested the list of all cluster workers.");
        List<Worker> workers = workerRegistryService.getAllWorkers();
        List<WorkerResponseDto> dtos = workers.stream()
                .map(WorkerResponseDto::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{workerId}")
    public ResponseEntity<AdminWorkerDetailResponseDto> getWorkerDetail(@PathVariable String workerId) {
        UUID parsedWorkerId = parseWorkerId(workerId);
        log.info("ADMIN: Requested worker detail for: {}", parsedWorkerId);
        return workerDetailQueryService.getWorkerDetail(parsedWorkerId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found."));
    }

    /**
     * Approves a pending worker without changing connection or availability state.
     */
    @PostMapping("/{workerId}/approve")
    public ResponseEntity<?> approveWorker(@PathVariable UUID workerId) {
        log.info("ADMIN: Attempting to approve worker: {}", workerId);

        try {
            String rawApiKey = workerRegistryService.approveWorker(workerId);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Worker has been approved.",
                    "apiKey", rawApiKey
            ));

        } catch (IllegalArgumentException e) {
            // Catches the "Worker not found" error and routes it to our GlobalExceptionHandler as a 404
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            // Catches the "Worker is not pending approval" error and routes it as a 400 Bad Request
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static UUID parseWorkerId(String workerId) {
        try {
            return UUID.fromString(workerId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workerId must be a valid UUID.");
        }
    }
}
