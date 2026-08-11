# Research Result Export

M32 adds read-only export endpoints for benchmark data recorded by M30 and produced by M31. The exports are intended for thesis analysis and reproducible research datasets.

M32 does not create benchmark runs, start the protocol comparison runner, create `WorkExecution`, create `ExecutionGroup`, schedule workers, mutate runtime state, or write export files to disk.

## Security

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API keys are rejected.
- Unauthenticated requests are rejected.
- USER role is rejected.

## Export Manifest

```http
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/exports
```

Returns the available export endpoints for one benchmark run:

```json
{
  "benchmarkRunId": "00000000-0000-0000-0000-000000000000",
  "generatedAt": "2026-08-11T15:30:00",
  "availableExports": [
    {
      "name": "dataset-json",
      "format": "JSON",
      "path": "/api/admin/research/benchmark-runs/00000000-0000-0000-0000-000000000000/exports/dataset.json",
      "description": "Complete benchmark dataset for thesis analysis."
    },
    {
      "name": "summary-csv",
      "format": "CSV",
      "path": "/api/admin/research/benchmark-runs/00000000-0000-0000-0000-000000000000/exports/summary.csv",
      "description": "Aggregated protocol/workload summary."
    },
    {
      "name": "scenarios-csv",
      "format": "CSV",
      "path": "/api/admin/research/benchmark-runs/00000000-0000-0000-0000-000000000000/exports/scenarios.csv",
      "description": "Scenario-level data."
    },
    {
      "name": "measurements-csv",
      "format": "CSV",
      "path": "/api/admin/research/benchmark-runs/00000000-0000-0000-0000-000000000000/exports/measurements.csv",
      "description": "Raw measurement data."
    },
    {
      "name": "events-csv",
      "format": "CSV",
      "path": "/api/admin/research/benchmark-runs/00000000-0000-0000-0000-000000000000/exports/events.csv",
      "description": "Benchmark event timeline."
    }
  ]
}
```

Unknown benchmark runs return `404`.

## JSON Dataset

```http
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/exports/dataset.json
```

Content type: `application/json`.

Response shape:

```json
{
  "schemaVersion": "1.0",
  "generatedAt": "2026-08-11T15:30:00",
  "benchmarkRun": {
    "benchmarkRunId": "00000000-0000-0000-0000-000000000000",
    "displayName": "Read model protocol comparison",
    "description": "Compare protocol adapters.",
    "status": "COMPLETED",
    "createdAt": "2026-08-11T15:00:00",
    "startedAt": "2026-08-11T15:00:01",
    "completedAt": "2026-08-11T15:00:10",
    "tags": ["m31", "read-model"],
    "notes": "M31 runner MVP."
  },
  "scenarios": [],
  "measurements": [],
  "events": [],
  "summary": {
    "scenarioCount": 9,
    "measurementCount": 27,
    "eventCount": 50,
    "protocols": [],
    "operations": [],
    "workloads": []
  }
}
```

The dataset contains safe benchmark run metadata, scenarios, measurements, events, and aggregated summaries. `generatedAt` is response-only and is not stored back to the database.

## CSV Exports

All CSV endpoints return UTF-8 `text/csv`, include a header row, sort deterministically, and export null values as empty fields.

CSV escaping follows standard quoting rules:

- fields containing comma, quote, carriage return, or newline are wrapped in quotes,
- embedded quotes are doubled,
- cells beginning with `=`, `+`, `-`, or `@` are prefixed with an apostrophe to reduce spreadsheet formula-injection risk.

### Summary CSV

```http
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/exports/summary.csv
```

Header:

```text
benchmarkRunId,displayName,status,protocol,workloadId,operation,scenarioCount,completedScenarioCount,failedScenarioCount,skippedScenarioCount,avgRequestLatencyMs,avgPayloadResponseBytes,totalErrorCount
```

Rows are grouped by `protocol`, `workloadId`, and `operation`, sorted by protocol, workload id, and operation.

### Scenarios CSV

```http
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/exports/scenarios.csv
```

Header:

```text
benchmarkRunId,scenarioId,scenarioIndex,displayName,workloadId,protocol,operation,dataTransferMode,payloadFormat,status,createdAt,startedAt,completedAt,executionId,executionGroupId,errorCode,errorMessage,notes
```

Rows are sorted by `scenarioIndex` ascending.

### Measurements CSV

```http
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/exports/measurements.csv
```

Header:

```text
benchmarkRunId,scenarioId,measurementId,type,valueNumeric,unit,recordedAt,notes
```

Rows are sorted by `recordedAt` ascending and then `measurementId`.

### Events CSV

```http
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/exports/events.csv
```

Header:

```text
benchmarkRunId,scenarioId,eventId,type,occurredAt,message,metadataJson,relatedExecutionId,relatedExecutionGroupId
```

Rows are sorted by `occurredAt` ascending and then `eventId`.

## Summary Aggregation

M32 computes summary values from M30 benchmark tables at request time:

- scenario counts grouped by protocol, workload id, and operation for `summary.csv`,
- protocol summaries for JSON grouped by protocol,
- operation summaries for JSON grouped by operation,
- workload summaries for JSON grouped by workload id,
- completed, failed, and skipped scenario counts from scenario status,
- average `REQUEST_LATENCY_MS`,
- average `PAYLOAD_RESPONSE_BYTES`,
- total `ERROR_COUNT`.

If a measurement type is missing for a group, JSON exports use `null` and CSV exports use an empty field. M32 does not add percentiles, charts, throughput derivation, or other derived statistics.

## Read-only Behavior

Export requests do not create, update, or delete:

- benchmark runs,
- benchmark scenarios,
- benchmark measurements,
- benchmark events,
- work executions,
- execution groups,
- execution assignments,
- artifacts.

Exports are generated in memory for the HTTP response. No export artifact or file is written to disk.

## Response Safety

Exports do not expose:

- raw execution configuration,
- raw merge plans,
- API keys,
- API key hashes,
- password hashes,
- lease tokens,
- lease hashes,
- worker secrets,
- local filesystem paths,
- physical artifact paths,
- stack traces,
- internal storage keys.

`metadataJson` is exported as stored benchmark metadata. M30 validates event metadata before it is recorded, and M32 still applies CSV formula-injection mitigation when that metadata is written to CSV.

## Roadmap Relation

M32 is a data export layer for existing benchmark records. It does not change the M31 protocol comparison runner. The larger combined runtime smoke remains deferred until after M33.

## Current Limitations

M32 does not implement:

- ZIP export,
- XLSX export,
- charts,
- frontend UI,
- full external network benchmark execution,
- automatic workload execution,
- execution group creation,
- artifact binary transfer comparison,
- runtime smoke testing,
- cleanup or retention policy changes.

## Future Extensions

Later milestones can add ZIP/XLSX packaging, richer statistics, chart generation, result export bundles, and thesis evidence packs.
