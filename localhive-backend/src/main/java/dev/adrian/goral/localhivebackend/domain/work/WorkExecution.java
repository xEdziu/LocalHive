package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "work_executions")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_version_id", nullable = false, updatable = false)
    @ToString.Exclude
    private WorkDefinitionVersion definitionVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instance_id", updatable = false)
    @ToString.Exclude
    private WorkInstance instance;

    @Column(name = "display_name_snapshot", nullable = false, length = WorkExecutionDisplayName.MAX_LENGTH, updatable = false)
    private String displayNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkExecutionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private LocalDateTime queuedAt;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_configuration_snapshot", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode resolvedConfigurationSnapshot;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "requiredRamMb", column = @Column(name = "resolved_required_ram_mb", nullable = false, updatable = false)),
            @AttributeOverride(name = "requiredCpuCores", column = @Column(name = "resolved_required_cpu_cores", nullable = false, updatable = false)),
            @AttributeOverride(name = "gpuRequired", column = @Column(name = "resolved_gpu_required", nullable = false, updatable = false))
    })
    private ResourceRequest resolvedResourceRequest;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    private WorkExecution(WorkDefinitionVersion definitionVersion,
                          WorkInstance instance,
                          String displayNameSnapshot,
                          JsonNode resolvedConfigurationSnapshot,
                          ResourceRequest resolvedResourceRequest,
                          LocalDateTime createdAt) {
        this.definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null.");
        requireInstanceMatchesDefinitionVersion(definitionVersion, instance);
        this.instance = instance;
        this.displayNameSnapshot = WorkExecutionDisplayName.resolve(
                displayNameSnapshot,
                definitionVersion,
                instance,
                requireObjectSnapshot(resolvedConfigurationSnapshot)
        );
        this.status = WorkExecutionStatus.QUEUED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.queuedAt = createdAt;
        this.resolvedConfigurationSnapshot = requireObjectSnapshot(resolvedConfigurationSnapshot).deepCopy();
        this.resolvedResourceRequest = Objects.requireNonNull(
                resolvedResourceRequest,
                "resolvedResourceRequest must not be null."
        );
    }

    public static WorkExecution createQueued(WorkDefinitionVersion definitionVersion,
                                             WorkInstance instance,
                                             JsonNode resolvedConfigurationSnapshot,
                                             ResourceRequest resolvedResourceRequest,
                                             String displayNameSnapshot,
                                             LocalDateTime createdAt) {
        return new WorkExecution(
                definitionVersion,
                instance,
                displayNameSnapshot,
                resolvedConfigurationSnapshot,
                resolvedResourceRequest,
                createdAt
        );
    }

    public static WorkExecution createQueued(WorkDefinitionVersion definitionVersion,
                                             WorkInstance instance,
                                             JsonNode resolvedConfigurationSnapshot,
                                             ResourceRequest resolvedResourceRequest,
                                             LocalDateTime createdAt) {
        return createQueued(
                definitionVersion,
                instance,
                resolvedConfigurationSnapshot,
                resolvedResourceRequest,
                null,
                createdAt
        );
    }

    public void markAssigned(LocalDateTime assignedAt) {
        requireStatus(WorkExecutionStatus.QUEUED, "mark execution as assigned");
        this.assignedAt = requireTimestamp(assignedAt, "assignedAt");
        this.status = WorkExecutionStatus.ASSIGNED;
    }

    public void markClaimed(LocalDateTime claimedAt) {
        requireStatus(WorkExecutionStatus.ASSIGNED, "mark execution as claimed");
        this.claimedAt = requireTimestamp(claimedAt, "claimedAt");
        this.status = WorkExecutionStatus.CLAIMED;
    }

    public void markRunning(LocalDateTime startedAt) {
        requireStatus(WorkExecutionStatus.CLAIMED, "mark execution as running");
        this.startedAt = requireTimestamp(startedAt, "startedAt");
        this.status = WorkExecutionStatus.RUNNING;
    }

    public void markSucceeded(LocalDateTime completedAt) {
        requireStatus(WorkExecutionStatus.RUNNING, "mark execution as succeeded");
        this.completedAt = requireTimestamp(completedAt, "completedAt");
        this.status = WorkExecutionStatus.SUCCEEDED;
    }

    public void markFailed(String failureCode, String failureMessage, LocalDateTime completedAt) {
        requireStatus(WorkExecutionStatus.RUNNING, "mark execution as failed");
        LocalDateTime failureCompletedAt = requireTimestamp(completedAt, "completedAt");
        String validFailureCode = requireNonBlank(failureCode, "failureCode");
        String validFailureMessage = requireNullableNonBlank(failureMessage, "failureMessage");

        this.completedAt = failureCompletedAt;
        this.failureCode = validFailureCode;
        this.failureMessage = validFailureMessage;
        this.status = WorkExecutionStatus.FAILED;
    }

    public void cancel(LocalDateTime cancelledAt) {
        requireOneOf(
                "cancel execution",
                WorkExecutionStatus.QUEUED,
                WorkExecutionStatus.ASSIGNED,
                WorkExecutionStatus.CLAIMED,
                WorkExecutionStatus.RUNNING
        );
        this.cancelledAt = requireTimestamp(cancelledAt, "cancelledAt");
        this.status = WorkExecutionStatus.CANCELLED;
    }

    public void cancelBeforeStart(String failureCode, String failureMessage, LocalDateTime cancelledAt) {
        requireOneOf("cancel execution before start", WorkExecutionStatus.QUEUED, WorkExecutionStatus.ASSIGNED);
        LocalDateTime cancellationTimestamp = requireTimestamp(cancelledAt, "cancelledAt");
        String validFailureCode = requireNonBlank(failureCode, "failureCode");
        String validFailureMessage = requireNullableNonBlank(failureMessage, "failureMessage");

        this.cancelledAt = cancellationTimestamp;
        this.completedAt = cancellationTimestamp;
        this.failureCode = validFailureCode;
        this.failureMessage = validFailureMessage;
        this.status = WorkExecutionStatus.CANCELLED;
    }

    public void expire(LocalDateTime expiredAt) {
        requireOneOf("expire execution", WorkExecutionStatus.ASSIGNED, WorkExecutionStatus.CLAIMED);
        this.expiredAt = requireTimestamp(expiredAt, "expiredAt");
        this.status = WorkExecutionStatus.EXPIRED;
    }

    public JsonNode getResolvedConfigurationSnapshot() {
        return resolvedConfigurationSnapshot.deepCopy();
    }

    private void requireStatus(WorkExecutionStatus expectedStatus, String action) {
        if (status != expectedStatus) {
            throw invalidTransition(action);
        }
    }

    private void requireOneOf(String action, WorkExecutionStatus... allowedStatuses) {
        for (WorkExecutionStatus allowedStatus : allowedStatuses) {
            if (status == allowedStatus) {
                return;
            }
        }

        throw invalidTransition(action);
    }

    private IllegalStateException invalidTransition(String action) {
        return new IllegalStateException("Cannot " + action + " from status " + status + ".");
    }

    private static void requireInstanceMatchesDefinitionVersion(WorkDefinitionVersion definitionVersion,
                                                               WorkInstance instance) {
        if (instance == null) {
            return;
        }

        WorkDefinitionVersion instanceDefinitionVersion = instance.getDefinitionVersion();
        boolean sameDefinitionVersion = definitionVersion == instanceDefinitionVersion
                || definitionVersion.getId() != null && definitionVersion.getId().equals(instanceDefinitionVersion.getId());

        if (!sameDefinitionVersion) {
            throw new IllegalArgumentException("instance must belong to the execution definitionVersion.");
        }
    }

    private static LocalDateTime requireTimestamp(LocalDateTime timestamp, String fieldName) {
        return Objects.requireNonNull(timestamp, fieldName + " must not be null.");
    }

    private static JsonNode requireObjectSnapshot(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) {
            throw new IllegalArgumentException("resolvedConfigurationSnapshot must be a non-null JSON object.");
        }

        return snapshot;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        return value;
    }

    private static String requireNullableNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank when present.");
        }

        return value;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        WorkExecution that = (WorkExecution) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
