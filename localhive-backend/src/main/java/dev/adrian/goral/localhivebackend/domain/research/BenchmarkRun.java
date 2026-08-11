package dev.adrian.goral.localhivebackend.domain.research;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "benchmark_runs")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkRun {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_NOTES_LENGTH = 4000;
    public static final int MAX_CREATED_BY_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "benchmark_run_id")
    private UUID id;

    @Column(name = "display_name", nullable = false, length = MAX_DISPLAY_NAME_LENGTH)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BenchmarkRunStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_by")
    private String createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode tags;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private BenchmarkRun(String displayName,
                         String description,
                         String createdBy,
                         JsonNode tags,
                         String notes,
                         LocalDateTime createdAt) {
        this.displayName = requireText(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
        this.description = optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
        this.status = BenchmarkRunStatus.CREATED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.createdBy = optionalText(createdBy, "createdBy", MAX_CREATED_BY_LENGTH);
        this.tags = tags == null ? null : tags.deepCopy();
        this.notes = optionalText(notes, "notes", MAX_NOTES_LENGTH);
    }

    public static BenchmarkRun create(String displayName,
                                      String description,
                                      String createdBy,
                                      JsonNode tags,
                                      String notes,
                                      LocalDateTime createdAt) {
        return new BenchmarkRun(displayName, description, createdBy, tags, notes, createdAt);
    }

    public void start(LocalDateTime startedAt) {
        if (status == BenchmarkRunStatus.RUNNING) {
            return;
        }
        requireNonTerminal("start benchmark run");

        LocalDateTime timestamp = Objects.requireNonNull(startedAt, "startedAt must not be null.");
        this.status = BenchmarkRunStatus.RUNNING;
        this.startedAt = timestamp;
    }

    public void complete(LocalDateTime completedAt) {
        requireNonTerminal("complete benchmark run");

        LocalDateTime timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        this.status = BenchmarkRunStatus.COMPLETED;
        if (startedAt == null) {
            this.startedAt = timestamp;
        }
        this.completedAt = timestamp;
    }

    public void fail(LocalDateTime completedAt) {
        requireNonTerminal("fail benchmark run");

        LocalDateTime timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        this.status = BenchmarkRunStatus.FAILED;
        if (startedAt == null) {
            this.startedAt = timestamp;
        }
        this.completedAt = timestamp;
    }

    public void cancel(LocalDateTime completedAt) {
        requireNonTerminal("cancel benchmark run");

        LocalDateTime timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        this.status = BenchmarkRunStatus.CANCELLED;
        if (startedAt == null) {
            this.startedAt = timestamp;
        }
        this.completedAt = timestamp;
    }

    public boolean isTerminal() {
        return status == BenchmarkRunStatus.COMPLETED
                || status == BenchmarkRunStatus.FAILED
                || status == BenchmarkRunStatus.CANCELLED;
    }

    public JsonNode getTags() {
        return tags == null ? null : tags.deepCopy();
    }

    private void requireNonTerminal(String action) {
        if (isTerminal()) {
            throw new IllegalStateException("Cannot " + action + " from status " + status + ".");
        }
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
        BenchmarkRun that = (BenchmarkRun) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
