package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerAuthService {

    private static final String WORKER_AUTHENTICATION_FAILED_MESSAGE = "Worker authentication failed.";

    private final WorkerRepository workerRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Verifies a worker's identity using API key authentication (Zero Trust).
     * 
     * @param workerId the UUID of the worker requesting authentication
     * @param rawApiKey the raw API key provided by the worker
     * @return the authenticated Worker entity
     * @throws BadCredentialsException if worker not found or API key is invalid
     */
    public Worker verifyWorker(UUID workerId, String rawApiKey) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> {
                    log.warn("Worker verification failed: Worker not found with ID: {}", workerId);
                    return new BadCredentialsException(WORKER_AUTHENTICATION_FAILED_MESSAGE);
                });

        if (worker.getApprovalStatus() != WorkerApprovalStatus.APPROVED) {
            log.warn("Worker verification failed: Worker {} is not approved", workerId);
            throw new BadCredentialsException(WORKER_AUTHENTICATION_FAILED_MESSAGE);
        }

        if (worker.getApiKeyHash() == null) {
            log.warn("Worker verification failed: Worker {} has no API key hash", workerId);
            throw new BadCredentialsException(WORKER_AUTHENTICATION_FAILED_MESSAGE);
        }

        if (!passwordEncoder.matches(rawApiKey, worker.getApiKeyHash())) {
            log.warn("Worker verification failed: Invalid API key for worker {}", workerId);
            throw new BadCredentialsException(WORKER_AUTHENTICATION_FAILED_MESSAGE);
        }

        log.debug("Worker {} successfully authenticated", workerId);
        return worker;
    }
}
