package dev.adrian.goral.localhivebackend.repository.research;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BenchmarkEventRepository extends JpaRepository<BenchmarkEvent, UUID> {

    long countByBenchmarkRun_Id(UUID benchmarkRunId);

    List<BenchmarkEvent> findByBenchmarkRun_IdOrderByOccurredAtAscIdAsc(UUID benchmarkRunId);
}
