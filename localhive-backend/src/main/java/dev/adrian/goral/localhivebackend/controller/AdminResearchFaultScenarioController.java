package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioCatalogResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioDescriptorResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchFaultScenarioValidationResponseDto;
import dev.adrian.goral.localhivebackend.service.research.ResearchFaultScenarioCatalogService;
import dev.adrian.goral.localhivebackend.service.research.ResearchFaultScenarioValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/research/fault-scenarios")
@RequiredArgsConstructor
public class AdminResearchFaultScenarioController {

    private final ResearchFaultScenarioCatalogService catalogService;
    private final ResearchFaultScenarioValidator validator;

    @GetMapping
    public ResponseEntity<AdminResearchFaultScenarioCatalogResponseDto> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    @GetMapping("/{scenarioId}")
    public ResponseEntity<AdminResearchFaultScenarioDescriptorResponseDto> getScenario(
            @PathVariable String scenarioId
    ) {
        return catalogService.getScenario(scenarioId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Research fault scenario not found."
                ));
    }

    @PostMapping("/validate")
    public ResponseEntity<AdminResearchFaultScenarioValidationResponseDto> validateScenario(
            @RequestBody AdminResearchFaultScenarioValidationRequestDto request
    ) {
        try {
            return ResponseEntity.ok(validator.validate(request));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
