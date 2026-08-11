package dev.adrian.goral.localhivebackend.repository.research;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BenchmarkScenarioRepository extends JpaRepository<BenchmarkScenario, UUID> {

    List<BenchmarkScenario> findByBenchmarkRun_IdOrderByScenarioIndexAsc(UUID benchmarkRunId);

    Optional<BenchmarkScenario> findTopByBenchmarkRun_IdOrderByScenarioIndexDesc(UUID benchmarkRunId);

    long countByBenchmarkRun_Id(UUID benchmarkRunId);
}
