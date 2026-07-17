package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationResolverTest {

    private final ConfigurationResolver resolver = new ConfigurationResolver();

    @Test
    void shouldReturnBaseCopyWhenOverridesAreEmpty() {
        ObjectNode base = JsonNodeFactory.instance.objectNode();
        base.put("value", 1);
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put("enabled", true);
        base.set("nested", nested);

        ObjectNode resolved = (ObjectNode) resolver.resolve(base, JsonNodeFactory.instance.objectNode());
        resolved.put("value", 999);
        ((ObjectNode) resolved.get("nested")).put("enabled", false);

        assertThat(base.get("value").intValue()).isEqualTo(1);
        assertThat(base.at("/nested/enabled").booleanValue()).isTrue();
    }

    @Test
    void shouldRecursivelyMergeObjectsAndReplaceScalarsAndArrays() {
        ObjectNode base = JsonNodeFactory.instance.objectNode();
        base.put("scalar", "base");
        base.put("unchanged", true);
        base.set("array", array(1, 2));
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put("first", 1);
        nested.put("second", 2);
        base.set("nested", nested);

        ObjectNode overrides = JsonNodeFactory.instance.objectNode();
        overrides.put("scalar", "override");
        overrides.set("array", array(3));
        overrides.set("explicitNull", JsonNodeFactory.instance.nullNode());
        ObjectNode nestedOverrides = JsonNodeFactory.instance.objectNode();
        nestedOverrides.put("second", 20);
        nestedOverrides.put("third", 30);
        overrides.set("nested", nestedOverrides);

        JsonNode resolved = resolver.resolve(base, overrides);

        assertThat(resolved.get("scalar").textValue()).isEqualTo("override");
        assertThat(resolved.get("unchanged").booleanValue()).isTrue();
        assertThat(resolved.get("array").size()).isEqualTo(1);
        assertThat(resolved.get("array").get(0).intValue()).isEqualTo(3);
        assertThat(resolved.get("explicitNull").isNull()).isTrue();
        assertThat(resolved.at("/nested/first").intValue()).isEqualTo(1);
        assertThat(resolved.at("/nested/second").intValue()).isEqualTo(20);
        assertThat(resolved.at("/nested/third").intValue()).isEqualTo(30);

        assertThat(base.get("scalar").textValue()).isEqualTo("base");
        assertThat(base.at("/nested/second").intValue()).isEqualTo(2);
        assertThat(overrides.get("scalar").textValue()).isEqualTo("override");
        assertThat(overrides.at("/nested/second").intValue()).isEqualTo(20);

        ((ObjectNode) resolved).put("scalar", "mutated");
        assertThat(base.get("scalar").textValue()).isEqualTo("base");
        assertThat(overrides.get("scalar").textValue()).isEqualTo("override");
    }

    @Test
    void shouldRejectNonObjectConfigurationInputs() {
        ObjectNode objectNode = JsonNodeFactory.instance.objectNode();

        assertThatThrownBy(() -> resolver.resolve(JsonNodeFactory.instance.arrayNode(), objectNode))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(objectNode, JsonNodeFactory.instance.arrayNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ArrayNode array(int... values) {
        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
        for (int value : values) {
            arrayNode.add(value);
        }
        return arrayNode;
    }
}
