package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerRegistryService {

    private final WorkerRepository workerRepository;

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
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Catches the unique constraint violation, avoiding race conditions
            log.warn("Registration rejected. A machine with hostname {} already exists.", hostname);
            throw new IllegalStateException("A machine with hostname " + hostname + " already exists in the registry.");
        }
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
}