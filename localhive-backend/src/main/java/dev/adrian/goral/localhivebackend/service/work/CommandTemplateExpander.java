package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommandTemplateExpander {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^{}]+)}}");

    private CommandTemplateExpander() {
    }

    static List<String> expand(JsonNode commandTemplate,
                               Map<String, String> placeholders,
                               String fieldPath) {
        if (commandTemplate == null || commandTemplate.isNull()) {
            throw new IllegalArgumentException(fieldPath + " is required.");
        }
        if (!commandTemplate.isArray() || commandTemplate.isEmpty()) {
            throw new IllegalArgumentException(fieldPath + " must be a non-empty array.");
        }

        List<String> command = new ArrayList<>();
        commandTemplate.forEach(element -> {
            if (!element.isTextual()) {
                throw new IllegalArgumentException(fieldPath + " elements must be text values.");
            }
            if (element.textValue().isBlank()) {
                throw new IllegalArgumentException(fieldPath + " elements must not be blank.");
            }
            command.add(expandPlaceholders(element.textValue(), placeholders));
        });
        return List.copyOf(command);
    }

    private static String expandPlaceholders(String value, Map<String, String> placeholders) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = placeholders.get(placeholder);
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "Unsupported commandTemplate placeholder: {{" + placeholder + "}}"
                );
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        String expanded = result.toString();
        if (expanded.contains("{{") || expanded.contains("}}")) {
            throw new IllegalArgumentException("Unsupported commandTemplate placeholder.");
        }
        return expanded;
    }
}
