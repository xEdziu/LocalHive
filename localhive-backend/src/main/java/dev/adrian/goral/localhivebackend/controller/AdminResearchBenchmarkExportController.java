package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.dto.ResearchBenchmarkDatasetDto;
import dev.adrian.goral.localhivebackend.dto.ResearchBenchmarkExportManifestDto;
import dev.adrian.goral.localhivebackend.service.research.ResearchBenchmarkExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/admin/research/benchmark-runs/{benchmarkRunId}/exports")
@RequiredArgsConstructor
public class AdminResearchBenchmarkExportController {

    private static final MediaType TEXT_CSV_UTF8 = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final ResearchBenchmarkExportService exportService;

    @GetMapping
    public ResponseEntity<ResearchBenchmarkExportManifestDto> manifest(@PathVariable UUID benchmarkRunId) {
        try {
            return ResponseEntity.ok(exportService.manifest(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping(value = "/dataset.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResearchBenchmarkDatasetDto> dataset(@PathVariable UUID benchmarkRunId) {
        try {
            return ResponseEntity.ok(exportService.dataset(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping(value = "/summary.csv", produces = "text/csv")
    public ResponseEntity<String> summaryCsv(@PathVariable UUID benchmarkRunId) {
        try {
            return csv(exportService.summaryCsv(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping(value = "/scenarios.csv", produces = "text/csv")
    public ResponseEntity<String> scenariosCsv(@PathVariable UUID benchmarkRunId) {
        try {
            return csv(exportService.scenariosCsv(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping(value = "/measurements.csv", produces = "text/csv")
    public ResponseEntity<String> measurementsCsv(@PathVariable UUID benchmarkRunId) {
        try {
            return csv(exportService.measurementsCsv(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping(value = "/events.csv", produces = "text/csv")
    public ResponseEntity<String> eventsCsv(@PathVariable UUID benchmarkRunId) {
        try {
            return csv(exportService.eventsCsv(benchmarkRunId));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    private static ResponseEntity<String> csv(String body) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .body(body);
    }
}
