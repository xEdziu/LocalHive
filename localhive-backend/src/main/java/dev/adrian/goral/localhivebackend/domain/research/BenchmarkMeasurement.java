package dev.adrian.goral.localhivebackend.domain.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.proxy.HibernateProxy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "benchmark_measurements")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkMeasurement {

    public static final int MAX_UNIT_LENGTH = 30;
    public static final int MAX_NOTES_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "benchmark_measurement_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "benchmark_run_id", nullable = false, updatable = false)
    @ToString.Exclude
    private BenchmarkRun benchmarkRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benchmark_scenario_id", updatable = false)
    @ToString.Exclude
    private BenchmarkScenario benchmarkScenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private BenchmarkMeasurementType type;

    @Column(name = "value_numeric", nullable = false, precision = 19, scale = 4)
    private BigDecimal valueNumeric;

    @Column(nullable = false, length = MAX_UNIT_LENGTH)
    private String unit;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private BenchmarkMeasurement(BenchmarkRun benchmarkRun,
                                 BenchmarkScenario benchmarkScenario,
                                 BenchmarkMeasurementType type,
                                 BigDecimal valueNumeric,
                                 String unit,
                                 String notes,
                                 LocalDateTime recordedAt) {
        this.benchmarkRun = Objects.requireNonNull(benchmarkRun, "benchmarkRun must not be null.");
        this.benchmarkScenario = benchmarkScenario;
        this.type = Objects.requireNonNull(type, "type must not be null.");
        this.valueNumeric = requireNonNegative(valueNumeric);
        this.unit = requireText(unit, "unit", MAX_UNIT_LENGTH);
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null.");
        this.notes = optionalText(notes, "notes", MAX_NOTES_LENGTH);
    }

    public static BenchmarkMeasurement create(BenchmarkRun benchmarkRun,
                                              BenchmarkScenario benchmarkScenario,
                                              BenchmarkMeasurementType type,
                                              BigDecimal valueNumeric,
                                              String unit,
                                              String notes,
                                              LocalDateTime recordedAt) {
        return new BenchmarkMeasurement(
                benchmarkRun,
                benchmarkScenario,
                type,
                valueNumeric,
                unit,
                notes,
                recordedAt
        );
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        BigDecimal validValue = Objects.requireNonNull(value, "valueNumeric must not be null.");
        if (validValue.signum() < 0) {
            throw new IllegalArgumentException("valueNumeric must be greater than or equal to 0.");
        }

        return validValue;
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not be longer than " + maxLength + " characters.");
        }

        return trimmed;
    }

    private static String optionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not be longer than " + maxLength + " characters.");
        }

        return trimmed;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        BenchmarkMeasurement that = (BenchmarkMeasurement) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
