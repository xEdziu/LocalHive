package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.dto.AdminWorkDefinitionDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminWorkDefinitionListResponseDto;
import dev.adrian.goral.localhivebackend.service.work.AdminWorkDefinitionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/work-definitions")
@RequiredArgsConstructor
public class AdminWorkDefinitionController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_OFFSET = 0;

    private final AdminWorkDefinitionQueryService queryService;

    @GetMapping
    public ResponseEntity<AdminWorkDefinitionListResponseDto> listDefinitions(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String logicalId
    ) {
        try {
            return ResponseEntity.ok(queryService.listDefinitions(
                    parseLimit(limit),
                    parseOffset(offset),
                    parseType(type),
                    logicalId
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{definitionId}")
    public ResponseEntity<AdminWorkDefinitionDetailResponseDto> getDefinition(@PathVariable String definitionId) {
        UUID parsedDefinitionId = parseDefinitionId(definitionId);
        return queryService.getDefinition(parsedDefinitionId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Work definition not found."));
    }

    private static int parseLimit(String rawLimit) {
        if (rawLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (rawLimit.isBlank()) {
            throw new IllegalArgumentException("limit must not be blank.");
        }

        try {
            return Integer.parseInt(rawLimit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be a whole number.");
        }
    }

    private static int parseOffset(String rawOffset) {
        if (rawOffset == null) {
            return DEFAULT_OFFSET;
        }
        if (rawOffset.isBlank()) {
            throw new IllegalArgumentException("offset must not be blank.");
        }

        try {
            return Integer.parseInt(rawOffset);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("offset must be a whole number.");
        }
    }

    private static WorkType parseType(String rawType) {
        if (rawType == null) {
            return null;
        }
        if (rawType.isBlank()) {
            throw new IllegalArgumentException("type must not be blank.");
        }

        try {
            return WorkType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown work definition type: " + rawType.trim());
        }
    }

    private static UUID parseDefinitionId(String rawDefinitionId) {
        try {
            return UUID.fromString(rawDefinitionId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "definitionId must be a valid UUID.");
        }
    }
}
