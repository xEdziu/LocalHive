package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
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

    private String generateSecureApiKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] randomBytes = new byte[32]; // 256 bits of entropy
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}