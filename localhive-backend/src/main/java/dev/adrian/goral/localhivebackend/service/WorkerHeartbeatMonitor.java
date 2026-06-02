package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerHeartbeatMonitor {

    private final WorkerRepository workerRepository;

    @Value("${worker.heartbeat.offline-timeout-seconds:30}")
    private long offlineTimeoutSeconds;

    @Scheduled(
            fixedDelayString = "${worker.heartbeat.check-interval-seconds:10}",
            initialDelayString = "${worker.heartbeat.check-interval-seconds:10}",
            timeUnit = TimeUnit.SECONDS
    )
    @Transactional
    public void checkAndMarkOfflineWorkers() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(offlineTimeoutSeconds);

        int updated = workerRepository.markInactiveWorkersOffline(
                WorkerStatus.ACTIVE,
                WorkerStatus.OFFLINE,
                cutoffTime
        );

        if (updated > 0) {
            log.warn("Marked {} worker(s) as OFFLINE (cutoff: {}).", updated, cutoffTime);
        }
    }
}