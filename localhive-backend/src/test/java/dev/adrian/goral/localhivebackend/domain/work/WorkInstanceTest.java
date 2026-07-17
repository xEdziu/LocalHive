package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkInstanceTest {

    @Test
    void shouldCreateEnabledInstanceWithIndependentConfigurationCopy() {
        WorkDefinitionVersion version = version("localhive.instance-create", 1);
        ObjectNode overrides = JsonNodeFactory.instance.objectNode();
        overrides.put("threads", 2);

        WorkInstance instance = WorkInstance.create(
                version,
                "Prime Search",
                overrides,
                ResourceRequestOverrides.of(1024, null, true),
                LocalDateTime.parse("2026-07-16T12:00:00")
        );

        overrides.put("threads", 99);
        ObjectNode returnedOverrides = (ObjectNode) instance.getConfigurationOverrides();
        returnedOverrides.put("threads", 100);

        assertThat(instance.isEnabled()).isTrue();
        assertThat(instance.getDisplayName()).isEqualTo("Prime Search");
        assertThat(instance.getConfigurationOverrides().get("threads").intValue()).isEqualTo(2);
        assertThat(instance.getResourceOverrides()).isEqualTo(ResourceRequestOverrides.of(1024, null, true));
        assertThat(instance.getUpdatedAt()).isEqualTo(instance.getCreatedAt());
    }

    @Test
    void shouldUpdateTimestampOnlyForRealMutations() {
        WorkInstance instance = WorkInstance.create(
                version("localhive.instance-mutation", 1),
                "Initial",
                LocalDateTime.parse("2026-07-16T12:00:00")
        );

        instance.rename("Initial", LocalDateTime.parse("2026-07-16T12:01:00"));
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:00:00"));

        instance.rename("Renamed", LocalDateTime.parse("2026-07-16T12:02:00"));
        assertThat(instance.getDisplayName()).isEqualTo("Renamed");
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:02:00"));

        instance.disable(LocalDateTime.parse("2026-07-16T12:03:00"));
        assertThat(instance.isEnabled()).isFalse();
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:03:00"));

        instance.disable(LocalDateTime.parse("2026-07-16T12:04:00"));
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:03:00"));

        instance.enable(LocalDateTime.parse("2026-07-16T12:05:00"));
        assertThat(instance.isEnabled()).isTrue();
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:05:00"));
    }

    @Test
    void shouldUpdateConfigurationAndResourceOverrides() {
        WorkInstance instance = WorkInstance.create(
                version("localhive.instance-update-overrides", 1),
                "Initial",
                LocalDateTime.parse("2026-07-16T12:00:00")
        );
        ObjectNode configurationOverrides = JsonNodeFactory.instance.objectNode();
        configurationOverrides.put("threads", 4);

        instance.updateConfigurationOverrides(
                configurationOverrides,
                LocalDateTime.parse("2026-07-16T12:01:00")
        );
        assertThat(instance.getConfigurationOverrides().get("threads").intValue()).isEqualTo(4);
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:01:00"));

        instance.updateResourceOverrides(
                ResourceRequestOverrides.of(2048, 2, false),
                LocalDateTime.parse("2026-07-16T12:02:00")
        );
        assertThat(instance.getResourceOverrides()).isEqualTo(ResourceRequestOverrides.of(2048, 2, false));
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:02:00"));

        instance.updateResourceOverrides(
                ResourceRequestOverrides.of(2048, 2, false),
                LocalDateTime.parse("2026-07-16T12:03:00")
        );
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:02:00"));
    }

    @Test
    void shouldUpgradeOnlyWithinSameWorkDefinition() {
        WorkDefinition definition = WorkDefinition.createLocal(
                "localhive.instance-upgrade",
                WorkType.TASK,
                LocalDateTime.parse("2026-07-16T12:00:00")
        );
        WorkDefinitionVersion firstVersion = version(definition, 1);
        WorkDefinitionVersion secondVersion = version(definition, 2);
        WorkDefinitionVersion otherDefinitionVersion = version("localhive.other-definition", 1);
        WorkInstance instance = WorkInstance.create(
                firstVersion,
                "Upgradeable",
                LocalDateTime.parse("2026-07-16T12:00:00")
        );

        instance.upgradeDefinitionVersion(firstVersion, LocalDateTime.parse("2026-07-16T12:01:00"));
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:00:00"));

        instance.upgradeDefinitionVersion(secondVersion, LocalDateTime.parse("2026-07-16T12:02:00"));
        assertThat(instance.getDefinitionVersion()).isSameAs(secondVersion);
        assertThat(instance.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-07-16T12:02:00"));

        assertThatThrownBy(() -> instance.upgradeDefinitionVersion(
                otherDefinitionVersion,
                LocalDateTime.parse("2026-07-16T12:03:00")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(instance.getDefinitionVersion()).isSameAs(secondVersion);
    }

    @Test
    void shouldRejectInvalidDisplayNameAndConfigurationOverrides() {
        WorkDefinitionVersion version = version("localhive.instance-validation", 1);

        assertThatThrownBy(() -> WorkInstance.create(
                null,
                "No Version",
                LocalDateTime.parse("2026-07-16T12:00:00")
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> WorkInstance.create(
                version,
                " ",
                LocalDateTime.parse("2026-07-16T12:00:00")
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> WorkInstance.create(
                version,
                "Invalid Config",
                JsonNodeFactory.instance.arrayNode(),
                ResourceRequestOverrides.empty(),
                LocalDateTime.parse("2026-07-16T12:00:00")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkDefinitionVersion version(String logicalIdentifier, int versionNumber) {
        return version(
                WorkDefinition.createLocal(
                        logicalIdentifier,
                        WorkType.TASK,
                        LocalDateTime.parse("2026-07-16T12:00:00")
                ),
                versionNumber
        );
    }

    private static WorkDefinitionVersion version(WorkDefinition definition, int versionNumber) {
        return WorkDefinitionVersion.createLocal(
                definition,
                versionNumber,
                "Definition v" + versionNumber,
                null,
                "localhive.executor",
                1,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                "0".repeat(64),
                LocalDateTime.parse("2026-07-16T12:00:00"),
                UUID.randomUUID()
        );
    }
}
