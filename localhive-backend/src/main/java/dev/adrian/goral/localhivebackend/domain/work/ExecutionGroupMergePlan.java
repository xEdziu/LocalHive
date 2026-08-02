package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
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
@Table(name = "execution_group_merge_plans")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionGroupMergePlan {

    @Id
    @Column(name = "execution_group_id")
    private UUID executionGroupId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "execution_group_id", nullable = false)
    @ToString.Exclude
    private ExecutionGroup executionGroup;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_version_id", nullable = false, updatable = false)
    @ToString.Exclude
    private WorkDefinitionVersion definitionVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_template", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode configurationTemplate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ExecutionGroupMergePlan(ExecutionGroup executionGroup,
                                    WorkDefinitionVersion definitionVersion,
                                    JsonNode configurationTemplate,
                                    LocalDateTime createdAt) {
        this.executionGroup = Objects.requireNonNull(executionGroup, "executionGroup must not be null.");
        this.definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null.");
        this.configurationTemplate = requireObjectTemplate(configurationTemplate).deepCopy();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
    }

    public static ExecutionGroupMergePlan create(ExecutionGroup executionGroup,
                                                 WorkDefinitionVersion definitionVersion,
                                                 JsonNode configurationTemplate,
                                                 LocalDateTime createdAt) {
        return new ExecutionGroupMergePlan(executionGroup, definitionVersion, configurationTemplate, createdAt);
    }

    public JsonNode getConfigurationTemplate() {
        return configurationTemplate.deepCopy();
    }

    private static JsonNode requireObjectTemplate(JsonNode configurationTemplate) {
        if (configurationTemplate == null || !configurationTemplate.isObject()) {
            throw new IllegalArgumentException("configurationTemplate must be a JSON object.");
        }

        return configurationTemplate;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        ExecutionGroupMergePlan that = (ExecutionGroupMergePlan) o;
        return getExecutionGroupId() != null && Objects.equals(getExecutionGroupId(), that.getExecutionGroupId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
