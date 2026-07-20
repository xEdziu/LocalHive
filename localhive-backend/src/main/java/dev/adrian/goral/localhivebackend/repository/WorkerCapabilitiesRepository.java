package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkerCapabilitiesRepository extends JpaRepository<WorkerCapabilities, UUID> {
}
