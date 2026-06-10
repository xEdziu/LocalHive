package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import dev.adrian.goral.localhivebackend.dto.WorkerHardwareUpdateRequestDto;
import dev.adrian.goral.localhivebackend.dto.WorkerHeartbeatRequestDto;
import dev.adrian.goral.localhivebackend.exception.DuplicateResourceException;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerRegistryService {

    private final WorkerRepository workerRepository;
    private final PasswordEncoder passwordEncoder;
    private final WorkerAuthService workerAuthService;

    /**
     * Registers a new agent requesting to join the cluster (Zero Trust approach).
     * The new worker always receives a PENDING status and requires Admin approval via UI.
     *
     * @return the saved Worker entity.
     * @throws IllegalStateException if a worker with the same hostname already exists.
     */
    @Transactional
    public Worker registerNewWorker(String hostname, String ipAddress, String osType,
                                    int totalRamMb, int sharedRamMb, int cpuCores, String gpuName) {

        Worker newWorker = Worker.builder()
                .hostname(hostname)
                .ipAddress(ipAddress)
                .osType(osType)
                .totalRamMb(totalRamMb)
                .sharedRamMb(sharedRamMb)
                .cpuCores(cpuCores)
                .gpuName(gpuName)
                .status(WorkerStatus.PENDING) // Explicitly wait for manual approval
                .build();

        try {
            Worker savedWorker = workerRepository.save(newWorker);
            log.info("New machine requested to join the cluster: {} ({})", hostname, ipAddress);
            return savedWorker;
        } catch (DataIntegrityViolationException e) {
            log.warn("Registration rejected. A machine with hostname {} already exists.", hostname);
            throw new DuplicateResourceException("hostname", hostname);
        }
    }

    /**
     * Retrieves all workers registered in the cluster.
     * @return a list of all Worker entities.
     */
    public List<Worker> getAllWorkers() {
        log.info("Fetching all workers from the registry.");
        return workerRepository.findAll();
    }

    /**
     * Updates only the worker allocation (shared RAM available for the swarm).
     *
     * @param workerId the UUID of the worker to update.
     * @param sharedRamMb the new shared RAM value.
     * @return the updated Worker entity.
     */
    @Transactional
    public Worker updateWorkerAllocation(UUID workerId, Integer sharedRamMb) {
        Worker worker = findWorkerOrThrow(workerId);

        if (sharedRamMb == null) {
            throw new IllegalStateException("Shared RAM must be provided.");
        }

        if (sharedRamMb > worker.getTotalRamMb()) {
            throw new IllegalStateException("sharedRamMb cannot be greater than totalRamMb");
        }

        worker.setSharedRamMb(sharedRamMb);

        return saveWorker(worker);
    }

    /**
     * Updates the hardware specification reported by the worker.
     * If total RAM changes, the request must also include a new shared RAM value.
     *
     * @param workerId the UUID of the worker to update.
     * @param requestDto the partial hardware update payload.
     * @return the updated Worker entity.
     */
    @Transactional
    public Worker updateWorkerHardwareSpec(UUID workerId, WorkerHardwareUpdateRequestDto requestDto) {
        Worker worker = findWorkerOrThrow(workerId);

        if (requestDto == null) {
            throw new IllegalStateException("At least one field must be provided.");
        }

        boolean hasAnyChange = false;

        if (requestDto.getHostname() != null) {
            worker.setHostname(requestDto.getHostname());
            hasAnyChange = true;
        }

        if (requestDto.getIpAddress() != null) {
            worker.setIpAddress(requestDto.getIpAddress());
            hasAnyChange = true;
        }

        if (requestDto.getOsType() != null) {
            worker.setOsType(requestDto.getOsType());
            hasAnyChange = true;
        }

        if (requestDto.getCpuCores() != null) {
            worker.setCpuCores(requestDto.getCpuCores());
            hasAnyChange = true;
        }

        if (requestDto.getGpuName() != null) {
            worker.setGpuName(requestDto.getGpuName());
            hasAnyChange = true;
        }

        if (requestDto.getSharedRamMb() != null && requestDto.getTotalRamMb() == null) {
            throw new IllegalStateException("sharedRamMb can only be changed through the allocation endpoint unless totalRamMb changes too.");
        }

        if (requestDto.getTotalRamMb() != null) {
            if (requestDto.getSharedRamMb() == null) {
                throw new IllegalStateException("sharedRamMb must be provided when totalRamMb changes.");
            }

            if (requestDto.getSharedRamMb() > requestDto.getTotalRamMb()) {
                throw new IllegalStateException("sharedRamMb cannot be greater than totalRamMb");
            }

            worker.setTotalRamMb(requestDto.getTotalRamMb());
            worker.setSharedRamMb(requestDto.getSharedRamMb());
            hasAnyChange = true;
        }

        if (!hasAnyChange) {
            throw new IllegalStateException("At least one field must be provided.");
        }

        return saveWorker(worker);
    }

    /**
     * Approves a worker by changing its status from PENDING to ACTIVE.
     * @param workerId the UUID of the worker to approve.
     * @return the raw API key that the worker can use for authentication (only returned once).
     * @throws IllegalArgumentException if the worker is not found.
     * @throws IllegalStateException if the worker is not in PENDING status.
     */
    @Transactional
    public String approveWorker(UUID workerId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found with ID: " + workerId));

        if (worker.getStatus() != WorkerStatus.PENDING) {
            throw new IllegalStateException("Worker is not in PENDING status. Current status: " + worker.getStatus());
        }

        String rawApiKey = generateSecureApiKey();
        String hashedApiKey = passwordEncoder.encode(rawApiKey);

        worker.setApiKeyHash(hashedApiKey);
        worker.setStatus(WorkerStatus.ACTIVE);
        workerRepository.save(worker);
        log.info("Worker {} ({}) has been approved and is now ACTIVE.", worker.getId(), worker.getHostname());

        return rawApiKey;
    }

    /**
     * Updates the last contact time for an active agent.
     * If the agent was marked as OFFLINE, it automatically changes status back to ACTIVE.
     *
     * @param workerId ID of the worker sending the heartbeat.
     */
    @Transactional
    public void recordHeartbeat(UUID workerId) {
        workerRepository.findById(workerId).ifPresentOrElse(worker -> {
            worker.setLastHeartbeatAt(LocalDateTime.now());

            // Auto-recovery: if a dead node wakes up, mark it as active again
            if (worker.getStatus() == WorkerStatus.OFFLINE) {
                worker.setStatus(WorkerStatus.ACTIVE);
                log.info("Machine {} has returned online and is now ACTIVE.", worker.getHostname());
            }
            workerRepository.save(worker);
        }, () -> log.warn("Received Heartbeat from an unknown Worker ID: {}", workerId));
    }

    /**
     * Handles extended heartbeat from an agent, updating worker state with pause info and RAM allocation.
     * Performs Zero Trust verification via API key when rawApiKey is provided (null when filter already validated).
     * Maps pauseEnabled flag to PAUSED or ACTIVE status.
     *
     * @param workerId ID of the worker sending the heartbeat.
     * @param rawApiKey Raw API key for authentication (can be null if pre-validated by filter).
     * @param request Heartbeat payload containing pauseEnabled and sharedRamMb.
     * @throws IllegalArgumentException if worker not found or API key is invalid.
     * @throws IllegalStateException if RAM validation fails.
     */
    @Transactional
    public void handleHeartbeat(UUID workerId, String rawApiKey, WorkerHeartbeatRequestDto request) {
        Worker worker;
        
        if (rawApiKey != null) {
            // API key provided - validate it (may be called outside request context)
            worker = workerAuthService.verifyWorker(workerId, rawApiKey);
        } else {
            // No API key provided - assume pre-validated by filter or other mechanism
            worker = workerRepository.findById(workerId)
                    .orElseThrow(() -> new IllegalArgumentException("Worker not found with ID: " + workerId));
        }

        worker.setLastHeartbeatAt(LocalDateTime.now());

        // Determine status based on pauseEnabled flag
        WorkerStatus newStatus = request.pauseEnabled() ? WorkerStatus.PAUSED : WorkerStatus.ACTIVE;

        // Auto-recovery: if a dead node wakes up, transition from OFFLINE appropriately
        if (worker.getStatus() == WorkerStatus.OFFLINE) {
            log.info("Machine {} has returned online and is now {}.", worker.getHostname(), newStatus);
        }

        worker.setStatus(newStatus);

        // Validate that sharedRamMb doesn't exceed totalRamMb
        if (request.sharedRamMb() > worker.getTotalRamMb()) {
            throw new IllegalStateException("sharedRamMb cannot be greater than totalRamMb");
        }

        worker.setSharedRamMb(request.sharedRamMb());

        workerRepository.save(worker);

        log.info("Heartbeat processed for worker {} - status: {}, sharedRamMb: {}",
                workerId, newStatus, request.sharedRamMb());
    }

    private Worker findWorkerOrThrow(UUID workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found with ID: " + workerId));
    }

    private Worker saveWorker(Worker worker) {
        try {
            return workerRepository.save(worker);
        } catch (DataIntegrityViolationException e) {
            log.warn("Update rejected. A machine with hostname {} already exists.", worker.getHostname());
            throw new DuplicateResourceException("hostname", worker.getHostname());
        }
    }

    private String generateSecureApiKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] randomBytes = new byte[32]; // 256 bits of entropy
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}