package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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
@Table(name = "work_instances")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_version_id", nullable = false)
    @ToString.Exclude
    private WorkDefinitionVersion definitionVersion;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_overrides", nullable = false, columnDefinition = "jsonb")
    private JsonNode configurationOverrides;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "requiredRamMb", column = @Column(name = "override_required_ram_mb")),
            @AttributeOverride(name = "requiredCpuCores", column = @Column(name = "override_required_cpu_cores")),
            @AttributeOverride(name = "gpuRequired", column = @Column(name = "override_gpu_required"))
    })
    private ResourceRequestOverrides resourceOverrides;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private WorkInstance(WorkDefinitionVersion definitionVersion,
                         String displayName,
                         JsonNode configurationOverrides,
                         ResourceRequestOverrides resourceOverrides,
                         LocalDateTime createdAt) {
        this.definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null.");
        this.displayName = requireNonBlankDisplayName(displayName);
        this.enabled = true;
        this.configurationOverrides = requireObjectConfigurationOverrides(configurationOverrides).deepCopy();
        this.resourceOverrides = defaultResourceOverrides(resourceOverrides);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.updatedAt = createdAt;
    }

    public static WorkInstance create(WorkDefinitionVersion definitionVersion,
                                      String displayName,
                                      JsonNode configurationOverrides,
                                      ResourceRequestOverrides resourceOverrides,
                                      LocalDateTime createdAt) {
        return new WorkInstance(definitionVersion, displayName, configurationOverrides, resourceOverrides, createdAt);
    }

    public static WorkInstance create(WorkDefinitionVersion definitionVersion,
                                      String displayName,
                                      LocalDateTime createdAt) {
        return create(
                definitionVersion,
                displayName,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty(),
                createdAt
        );
    }

    public void rename(String displayName, LocalDateTime changedAt) {
        String newDisplayName = requireNonBlankDisplayName(displayName);
        if (this.displayName.equals(newDisplayName)) {
            return;
        }

        this.displayName = newDisplayName;
        touch(changedAt);
    }

    public void enable(LocalDateTime changedAt) {
        if (enabled) {
            return;
        }

        enabled = true;
        touch(changedAt);
    }

    public void disable(LocalDateTime changedAt) {
        if (!enabled) {
            return;
        }

        enabled = false;
        touch(changedAt);
    }

    public void updateConfigurationOverrides(JsonNode configurationOverrides, LocalDateTime changedAt) {
        JsonNode newConfigurationOverrides = requireObjectConfigurationOverrides(configurationOverrides).deepCopy();
        if (this.configurationOverrides.equals(newConfigurationOverrides)) {
            return;
        }

        this.configurationOverrides = newConfigurationOverrides;
        touch(changedAt);
    }

    public void updateResourceOverrides(ResourceRequestOverrides resourceOverrides, LocalDateTime changedAt) {
        ResourceRequestOverrides newResourceOverrides = defaultResourceOverrides(resourceOverrides);
        if (this.resourceOverrides.equals(newResourceOverrides)) {
            return;
        }

        this.resourceOverrides = newResourceOverrides;
        touch(changedAt);
    }

    public void upgradeDefinitionVersion(WorkDefinitionVersion newDefinitionVersion, LocalDateTime changedAt) {
        WorkDefinitionVersion targetVersion = Objects.requireNonNull(
                newDefinitionVersion,
                "newDefinitionVersion must not be null."
        );
        if (sameDefinitionVersion(targetVersion)) {
            return;
        }
        requireSameDefinition(targetVersion);

        this.definitionVersion = targetVersion;
        touch(changedAt);
    }

    public JsonNode getConfigurationOverrides() {
        return configurationOverrides.deepCopy();
    }

    private boolean sameDefinitionVersion(WorkDefinitionVersion otherVersion) {
        return definitionVersion == otherVersion
                || definitionVersion.getId() != null && definitionVersion.getId().equals(otherVersion.getId());
    }

    private void requireSameDefinition(WorkDefinitionVersion otherVersion) {
        WorkDefinition currentDefinition = definitionVersion.getDefinition();
        WorkDefinition newDefinition = otherVersion.getDefinition();
        boolean sameDefinition = currentDefinition == newDefinition
                || currentDefinition.getId() != null && currentDefinition.getId().equals(newDefinition.getId());

        if (!sameDefinition) {
            throw new IllegalArgumentException("newDefinitionVersion must belong to the same work definition.");
        }
    }

    private void touch(LocalDateTime changedAt) {
        this.updatedAt = Objects.requireNonNull(changedAt, "changedAt must not be null.");
    }

    private static ResourceRequestOverrides defaultResourceOverrides(ResourceRequestOverrides resourceOverrides) {
        return resourceOverrides == null ? ResourceRequestOverrides.empty() : resourceOverrides;
    }

    private static String requireNonBlankDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank.");
        }

        return displayName;
    }

    private static JsonNode requireObjectConfigurationOverrides(JsonNode configurationOverrides) {
        if (configurationOverrides == null || !configurationOverrides.isObject()) {
            throw new IllegalArgumentException("configurationOverrides must be a non-null JSON object.");
        }

        return configurationOverrides;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        WorkInstance that = (WorkInstance) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
