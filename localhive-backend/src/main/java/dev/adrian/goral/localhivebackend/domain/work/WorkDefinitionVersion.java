package dev.adrian.goral.localhivebackend.domain.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionApprovalStatus;
import dev.adrian.goral.localhivebackend.service.work.DefinitionValidation;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "work_definition_versions")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkDefinitionVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false, updatable = false)
    @ToString.Exclude
    private WorkDefinition definition;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String description;

    @Column(name = "executor_id", nullable = false, updatable = false)
    private String executorId;

    @Column(name = "executor_contract_version", nullable = false, updatable = false)
    private int executorContractVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executor_configuration", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode executorConfiguration;

    @Column(name = "content_checksum", nullable = false, length = 64, updatable = false)
    private String contentChecksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private DefinitionApprovalStatus approvalStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "imported_at", updatable = false)
    private LocalDateTime importedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    private WorkDefinitionVersion(WorkDefinition definition,
                                  int versionNumber,
                                  String name,
                                  String description,
                                  String executorId,
                                  int executorContractVersion,
                                  JsonNode executorConfiguration,
                                  String contentChecksum,
                                  DefinitionApprovalStatus approvalStatus,
                                  LocalDateTime createdAt,
                                  UUID createdByUserId,
                                  LocalDateTime importedAt,
                                  LocalDateTime reviewedAt,
                                  UUID reviewedByUserId) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null.");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be greater than or equal to 1.");
        }
        this.versionNumber = versionNumber;
        this.name = DefinitionValidation.requireNonBlankName(name);
        this.description = description;
        this.executorId = DefinitionValidation.requireValidExecutorId(executorId);
        DefinitionValidation.requirePositiveExecutorContractVersion(executorContractVersion);
        this.executorContractVersion = executorContractVersion;
        this.executorConfiguration = DefinitionValidation.requireObjectConfiguration(executorConfiguration).deepCopy();
        this.contentChecksum = Objects.requireNonNull(contentChecksum, "contentChecksum must not be null.");
        this.approvalStatus = Objects.requireNonNull(approvalStatus, "approvalStatus must not be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.createdByUserId = DefinitionValidation.requireUserId(createdByUserId, "createdByUserId");
        this.importedAt = importedAt;
        this.reviewedAt = reviewedAt;
        this.reviewedByUserId = reviewedByUserId;
    }

    public static WorkDefinitionVersion createLocal(WorkDefinition definition,
                                                    int versionNumber,
                                                    String name,
                                                    String description,
                                                    String executorId,
                                                    int executorContractVersion,
                                                    JsonNode executorConfiguration,
                                                    String contentChecksum,
                                                    LocalDateTime createdAt,
                                                    UUID createdByUserId) {
        return new WorkDefinitionVersion(
                definition,
                versionNumber,
                name,
                description,
                executorId,
                executorContractVersion,
                executorConfiguration,
                contentChecksum,
                DefinitionApprovalStatus.APPROVED,
                createdAt,
                createdByUserId,
                null,
                createdAt,
                createdByUserId
        );
    }

    public static WorkDefinitionVersion createImported(WorkDefinition definition,
                                                       int versionNumber,
                                                       String name,
                                                       String description,
                                                       String executorId,
                                                       int executorContractVersion,
                                                       JsonNode executorConfiguration,
                                                       String contentChecksum,
                                                       LocalDateTime createdAt,
                                                       UUID createdByUserId) {
        return new WorkDefinitionVersion(
                definition,
                versionNumber,
                name,
                description,
                executorId,
                executorContractVersion,
                executorConfiguration,
                contentChecksum,
                DefinitionApprovalStatus.PENDING,
                createdAt,
                createdByUserId,
                createdAt,
                null,
                null
        );
    }

    public void approve(LocalDateTime reviewedAt, UUID reviewedByUserId) {
        review(DefinitionApprovalStatus.APPROVED, reviewedAt, reviewedByUserId);
    }

    public void reject(LocalDateTime reviewedAt, UUID reviewedByUserId) {
        review(DefinitionApprovalStatus.REJECTED, reviewedAt, reviewedByUserId);
    }

    public JsonNode getExecutorConfiguration() {
        return executorConfiguration.deepCopy();
    }

    private void review(DefinitionApprovalStatus targetStatus, LocalDateTime reviewedAt, UUID reviewedByUserId) {
        if (approvalStatus != DefinitionApprovalStatus.PENDING) {
            throw new IllegalStateException("Definition version review is terminal. Current status: " + approvalStatus);
        }

        this.approvalStatus = Objects.requireNonNull(targetStatus, "targetStatus must not be null.");
        this.reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt must not be null.");
        this.reviewedByUserId = DefinitionValidation.requireUserId(reviewedByUserId, "reviewedByUserId");
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        WorkDefinitionVersion that = (WorkDefinitionVersion) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
