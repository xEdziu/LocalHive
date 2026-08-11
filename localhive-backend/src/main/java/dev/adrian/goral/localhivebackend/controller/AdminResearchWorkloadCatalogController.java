package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadCatalogResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadDescriptorResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchWorkloadValidationResponseDto;
import dev.adrian.goral.localhivebackend.service.research.ResearchWorkloadCatalogService;
import dev.adrian.goral.localhivebackend.service.research.ResearchWorkloadCatalogValidator;
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
@RequestMapping("/api/admin/research/workload-catalog")
@RequiredArgsConstructor
public class AdminResearchWorkloadCatalogController {

    private final ResearchWorkloadCatalogService catalogService;
    private final ResearchWorkloadCatalogValidator validator;

    @GetMapping
    public ResponseEntity<AdminResearchWorkloadCatalogResponseDto> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    @GetMapping("/{workloadId}")
    public ResponseEntity<AdminResearchWorkloadDescriptorResponseDto> getWorkload(@PathVariable String workloadId) {
        return catalogService.getWorkload(workloadId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Research workload not found."));
    }

    @PostMapping("/validate")
    public ResponseEntity<AdminResearchWorkloadValidationResponseDto> validateWorkload(
            @RequestBody AdminResearchWorkloadValidationRequestDto request
    ) {
        try {
            return ResponseEntity.ok(validator.validate(request));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
