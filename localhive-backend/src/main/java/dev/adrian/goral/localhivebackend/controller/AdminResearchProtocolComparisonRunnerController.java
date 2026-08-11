package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.dto.AdminProtocolComparisonRunRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminProtocolComparisonRunResponseDto;
import dev.adrian.goral.localhivebackend.service.research.ResearchProtocolComparisonRunnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin/research/protocol-comparison-runs")
@RequiredArgsConstructor
public class AdminResearchProtocolComparisonRunnerController {

    private final ResearchProtocolComparisonRunnerService runnerService;

    @PostMapping
    public ResponseEntity<AdminProtocolComparisonRunResponseDto> createComparisonRun(
            @RequestBody AdminProtocolComparisonRunRequestDto request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(runnerService.run(
                    request,
                    authentication == null ? null : authentication.getName()
            ));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
