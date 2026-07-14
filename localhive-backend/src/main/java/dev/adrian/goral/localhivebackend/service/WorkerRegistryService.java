package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
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
     * The new worker starts pending approval and offline until an authenticated heartbeat arrives.
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
                .approvalStatus(WorkerApprovalStatus.PENDING)
                .connectionStatus(WorkerConnectionStatus.OFFLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
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
     * Approves a worker without changing current connection or availability state.
     * @param workerId the UUID of the worker to approve.
     * @return the raw API key that the worker can use for authentication (only returned once).
     * @throws IllegalArgumentException if the worker is not found.
     * @throws IllegalStateException if the worker is not pending approval.
     */
    @Transactional
    public String approveWorker(UUID workerId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found with ID: " + workerId));

        if (worker.getApprovalStatus() != WorkerApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "Worker is not in PENDING approval status. Current approvalStatus: "
                            + worker.getApprovalStatus()
            );
        }

        String rawApiKey = generateSecureApiKey();
        String hashedApiKey = passwordEncoder.encode(rawApiKey);

        worker.setApiKeyHash(hashedApiKey);
        worker.setApprovalStatus(WorkerApprovalStatus.APPROVED);
        workerRepository.save(worker);
        log.info("Worker {} ({}) has been approved.", worker.getId(), worker.getHostname());

        return rawApiKey;
    }

    @Transactional
    public void handleHeartbeat(UUID workerId, String rawApiKey, WorkerHeartbeatRequestDto request) {
        Worker worker = workerAuthService.verifyWorker(workerId, rawApiKey);

        if (request.sharedRamMb() > worker.getTotalRamMb()) {
            throw new IllegalStateException("sharedRamMb cannot be greater than totalRamMb");
        }

        WorkerAvailabilityStatus newAvailability = request.pauseEnabled()
                ? WorkerAvailabilityStatus.PAUSED
                : WorkerAvailabilityStatus.AVAILABLE;

        if (worker.getConnectionStatus() == WorkerConnectionStatus.OFFLINE) {
            log.info("Machine {} has returned online and is now {}.", worker.getHostname(), newAvailability);
        }

        worker.setLastHeartbeatAt(LocalDateTime.now());
        worker.setConnectionStatus(WorkerConnectionStatus.ONLINE);
        worker.setAvailabilityStatus(newAvailability);
        worker.setSharedRamMb(request.sharedRamMb());

        workerRepository.save(worker);

        log.info("Heartbeat processed for worker {} - connection: {}, availability: {}, sharedRamMb: {}",
                workerId, WorkerConnectionStatus.ONLINE, newAvailability, request.sharedRamMb());
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
