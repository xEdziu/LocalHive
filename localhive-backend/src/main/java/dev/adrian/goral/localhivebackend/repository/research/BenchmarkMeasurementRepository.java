package dev.adrian.goral.localhivebackend.repository.research;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurement;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BenchmarkMeasurementRepository extends JpaRepository<BenchmarkMeasurement, UUID> {

    long countByBenchmarkRun_Id(UUID benchmarkRunId);

    @Query("""
            SELECT measurement
            FROM BenchmarkMeasurement measurement
            WHERE measurement.benchmarkRun.id = :benchmarkRunId
              AND (:scenarioId IS NULL OR measurement.benchmarkScenario.id = :scenarioId)
              AND (:type IS NULL OR measurement.type = :type)
            ORDER BY measurement.recordedAt ASC, measurement.id ASC
            """)
    List<BenchmarkMeasurement> findAdminMeasurements(
            @Param("benchmarkRunId") UUID benchmarkRunId,
            @Param("scenarioId") UUID scenarioId,
            @Param("type") BenchmarkMeasurementType type
    );
}
