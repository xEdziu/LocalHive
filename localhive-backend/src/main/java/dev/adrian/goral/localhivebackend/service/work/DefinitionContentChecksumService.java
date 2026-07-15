package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class DefinitionContentChecksumService {

    private final JsonMapper jsonMapper;

    public String calculateChecksum(String logicalIdentifier,
                                    WorkType workType,
                                    String name,
                                    String description,
                                    String executorId,
                                    int executorContractVersion,
                                    JsonNode executorConfiguration) {
        ObjectNode canonicalContent = JsonNodeFactory.instance.objectNode();
        canonicalContent.put("logicalIdentifier", logicalIdentifier);
        canonicalContent.put("workType", workType.name());
        canonicalContent.put("name", name);
        canonicalContent.set(
                "description",
                description == null
                        ? JsonNodeFactory.instance.nullNode()
                        : JsonNodeFactory.instance.textNode(description)
        );
        canonicalContent.put("executorId", executorId);
        canonicalContent.put("executorContractVersion", executorContractVersion);
        canonicalContent.set("executorConfiguration", canonicalize(executorConfiguration));

        byte[] serialized = serialize(canonicalize(canonicalContent));
        byte[] digest = sha256(serialized);
        return HexFormat.of().formatHex(digest);
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }

        if (node.isObject()) {
            ObjectNode sortedObject = JsonNodeFactory.instance.objectNode();
            StreamSupport.stream(node.properties().spliterator(), false)
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> sortedObject.set(entry.getKey(), canonicalize(entry.getValue())));
            return sortedObject;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> arrayNode.add(canonicalize(element)));
            return arrayNode;
        }

        return node.deepCopy();
    }

    private byte[] serialize(JsonNode canonicalContent) {
        try {
            return jsonMapper.writeValueAsString(canonicalContent).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Definition content checksum serialization failed.", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", e);
        }
    }
}
