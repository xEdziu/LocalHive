package dev.adrian.goral.localhivebackend.domain.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
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
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "execution_groups")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionGroup {

    public static final int MAX_DISPLAY_NAME_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "display_name", nullable = false, length = MAX_DISPLAY_NAME_LENGTH)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionGroupStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "merge_mode", nullable = false)
    private ExecutionGroupMergeMode mergeMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_policy", nullable = false)
    private ExecutionGroupFailurePolicy failurePolicy;

    @Column(name = "shard_count", nullable = false)
    private int shardCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    private ExecutionGroup(String displayName,
                           ExecutionGroupMergeMode mergeMode,
                           ExecutionGroupFailurePolicy failurePolicy,
                           int shardCount,
                           LocalDateTime createdAt) {
        this.displayName = requireDisplayName(displayName);
        this.status = ExecutionGroupStatus.CREATED;
        this.mergeMode = Objects.requireNonNull(mergeMode, "mergeMode must not be null.");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null.");
        this.shardCount = requirePositiveShardCount(shardCount);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.updatedAt = createdAt;
    }

    public static ExecutionGroup create(String displayName,
                                        ExecutionGroupMergeMode mergeMode,
                                        ExecutionGroupFailurePolicy failurePolicy,
                                        int shardCount,
                                        LocalDateTime createdAt) {
        return new ExecutionGroup(displayName, mergeMode, failurePolicy, shardCount, createdAt);
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank.");
        }

        String trimmed = displayName.trim();
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName must not be longer than 255 characters.");
        }

        return trimmed;
    }

    private static int requirePositiveShardCount(int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be greater than 0.");
        }

        return shardCount;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        ExecutionGroup that = (ExecutionGroup) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
