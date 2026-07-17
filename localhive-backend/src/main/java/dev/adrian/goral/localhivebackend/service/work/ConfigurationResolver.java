package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class ConfigurationResolver {

    public JsonNode resolve(JsonNode baseConfiguration, JsonNode configurationOverrides) {
        ObjectNode base = requireObject(baseConfiguration, "baseConfiguration");
        ObjectNode overrides = requireObject(configurationOverrides, "configurationOverrides");

        return merge(base, overrides);
    }

    private static ObjectNode merge(ObjectNode baseConfiguration, ObjectNode configurationOverrides) {
        ObjectNode result = baseConfiguration.deepCopy();
        configurationOverrides.properties().forEach(entry -> {
            JsonNode baseValue = result.get(entry.getKey());
            JsonNode overrideValue = entry.getValue();

            if (baseValue != null && baseValue.isObject() && overrideValue != null && overrideValue.isObject()) {
                result.set(entry.getKey(), merge((ObjectNode) baseValue, (ObjectNode) overrideValue));
            } else {
                result.set(
                        entry.getKey(),
                        overrideValue == null ? JsonNodeFactory.instance.nullNode() : overrideValue.deepCopy()
                );
            }
        });
        return result;
    }

    private static ObjectNode requireObject(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(fieldName + " must be a non-null JSON object.");
        }

        return (ObjectNode) node;
    }
}
