# Research Benchmark Recorder

M30 adds a persistent benchmark recorder for thesis and protocol comparison work. It stores benchmark runs, scenarios, measurements, and safe event notes.

This is a recorder foundation. M31 adds a read-only [Research Protocol Comparison Runner](research-protocol-comparison-runner.md) on top of it. The recorder itself does not run workloads, create `WorkExecution`, create `ExecutionGroup`, schedule workers, upload workspace artifacts, download output artifacts, or export datasets. The combined runtime smoke remains deferred until after M33.

## Purpose

The recorder gives later milestones a stable place to store:

- benchmark run metadata,
- protocol/workload scenario dimensions,
- numeric measurements,
- safe timeline events and notes.

It reuses the existing research protocol and workload vocabulary:

- `ResearchProtocol`,
- `ResearchOperation`,
- `ResearchDataTransferMode`,
- `ResearchPayloadFormat`,
- M29 workload catalog ids.

## Database Model

M30 adds Flyway migration `V15__create_benchmark_recorder_tables.sql`.

Tables:

- `benchmark_runs`
- `benchmark_scenarios`
- `benchmark_measurements`
- `benchmark_events`

`benchmark_scenarios.execution_id` and `benchmark_scenarios.execution_group_id` are nullable UUID links. They are not foreign keys to runtime tables in M30, so benchmark records do not affect execution lifecycle, deletion, assignment, cancel, reconcile, artifact storage, or worker claim behavior.

`benchmark_runs.tags` is stored as JSONB. `benchmark_events.metadata_json` is stored as text after JSON validation and metadata safety checks.

## Endpoints

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

### Create Run

```http
POST /api/admin/research/benchmark-runs
```

Request:

```json
{
  "displayName": "Protocol comparison baseline",
  "description": "Manual run for REST/WebSocket/SOAP comparison.",
  "tags": ["baseline", "manual"],
  "notes": "Initial recorder test."
}
```

Validation:

- `displayName` required, nonblank, max 200,
- `description` max 2000,
- `notes` max 4000,
- `tags` max 20 items,
- each tag required, nonblank, max 50.

The response includes `benchmarkRunId`, run metadata, `status = CREATED`, timestamps, tags, notes, and summary counts.

### List And Detail Runs

```http
GET /api/admin/research/benchmark-runs
GET /api/admin/research/benchmark-runs/{benchmarkRunId}
```

List results are newest-first. The optional `status` query parameter filters by `BenchmarkRunStatus`.

Run detail returns the run metadata plus counts:

- `scenarioCount`,
- `measurementCount`,
- `eventCount`.

It does not return all scenarios, measurements, or events inline.

### Run Lifecycle

```http
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/start
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/complete
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/fail
```

Run statuses:

- `CREATED`,
- `RUNNING`,
- `COMPLETED`,
- `FAILED`,
- `CANCELLED`.

Transitions:

- `start`: `CREATED -> RUNNING`,
- `start` is idempotent for `RUNNING`,
- `complete`: `CREATED` or `RUNNING -> COMPLETED`,
- `fail`: `CREATED` or `RUNNING -> FAILED`,
- later transitions from terminal statuses return `409`.

Lifecycle transitions record safe benchmark events.

### Add And List Scenarios

```http
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/scenarios
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/scenarios
```

Request:

```json
{
  "displayName": "REST sharded optimization",
  "workloadId": "SHARDED_OPTIMIZATION_4",
  "protocol": "REST",
  "operation": "CREATE_EXECUTION_GROUP",
  "dataTransferMode": "INLINE_JSON",
  "payloadFormat": "JSON",
  "notes": "Manual scenario"
}
```

Scenario validation:

- the run must exist,
- terminal runs reject new scenarios with `409`,
- `scenarioIndex` is assigned automatically per run,
- workload/protocol/operation/data-transfer/payload combination is validated through the M29 workload catalog validator,
- invalid combinations return `400` with a safe reason,
- no `WorkExecution` or `ExecutionGroup` is created.

Scenarios are listed by `scenarioIndex` ascending.

### Scenario Lifecycle

```http
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/scenarios/{scenarioId}/start
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/scenarios/{scenarioId}/complete
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/scenarios/{scenarioId}/fail
```

Scenario statuses:

- `CREATED`,
- `RUNNING`,
- `COMPLETED`,
- `FAILED`,
- `CANCELLED`,
- `SKIPPED`.

Transitions:

- `start`: `CREATED -> RUNNING`,
- `start` is idempotent for `RUNNING`,
- `complete`: `CREATED` or `RUNNING -> COMPLETED`,
- `fail`: `CREATED` or `RUNNING -> FAILED`,
- later transitions from terminal statuses return `409`.

Scenario failure accepts optional `errorCode` and `errorMessage`. Stored error metadata is trimmed and redacted for obvious credential headers and filesystem paths.

### Measurements

```http
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/measurements
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/measurements
```

Request:

```json
{
  "scenarioId": "00000000-0000-0000-0000-000000000000",
  "type": "REQUEST_LATENCY_MS",
  "valueNumeric": 123.45,
  "unit": "ms",
  "notes": "optional"
}
```

Validation:

- run must exist,
- `scenarioId`, when present, must belong to the run,
- `type` required,
- `valueNumeric` required and nonnegative,
- `unit` required, nonblank, max 30,
- `notes` max 1000.

Measurements are allowed after a run reaches a terminal status because benchmark measurements may be recorded during post-processing. Query filters:

- `scenarioId`,
- `type`.

### Events

```http
POST /api/admin/research/benchmark-runs/{benchmarkRunId}/events
GET /api/admin/research/benchmark-runs/{benchmarkRunId}/events
```

Request:

```json
{
  "scenarioId": "00000000-0000-0000-0000-000000000000",
  "type": "NOTE_RECORDED",
  "message": "Manual note.",
  "metadataJson": "{}",
  "relatedExecutionId": "00000000-0000-0000-0000-000000000000",
  "relatedExecutionGroupId": "00000000-0000-0000-0000-000000000000"
}
```

Validation:

- run must exist,
- `scenarioId`, when present, must belong to the run,
- `type` required,
- `message` required, nonblank, max 2000,
- `metadataJson` optional, valid JSON, max 8000,
- metadata rejects obvious secret-like keys such as `token`, `apiKey`, `password`, `secret`, `leaseToken`, and `leaseHash`,
- metadata rejects obvious Windows and Linux absolute filesystem paths.

Events are allowed after terminal run status for post-run notes. Events are sorted by `occurredAt` ascending and then id.

## Measurement Types

Supported `BenchmarkMeasurementType` values:

- `REQUEST_LATENCY_MS`
- `END_TO_END_TIME_MS`
- `TIME_TO_FIRST_STATUS_MS`
- `TIME_TO_FINAL_STATUS_MS`
- `PAYLOAD_REQUEST_BYTES`
- `PAYLOAD_RESPONSE_BYTES`
- `SERIALIZATION_TIME_MS`
- `DESERIALIZATION_TIME_MS`
- `ARTIFACT_UPLOAD_TIME_MS`
- `ARTIFACT_DOWNLOAD_TIME_MS`
- `ROUND_TRIP_COUNT`
- `ERROR_COUNT`
- `RETRY_COUNT`
- `THROUGHPUT_JOBS_PER_MINUTE`

## Event Types

Supported `BenchmarkEventType` values:

- `RUN_CREATED`
- `RUN_STARTED`
- `RUN_COMPLETED`
- `RUN_FAILED`
- `RUN_CANCELLED`
- `SCENARIO_CREATED`
- `SCENARIO_STARTED`
- `SCENARIO_COMPLETED`
- `SCENARIO_FAILED`
- `SCENARIO_SKIPPED`
- `MEASUREMENT_RECORDED`
- `NOTE_RECORDED`

## Security

Recorder responses expose only benchmark metadata, scenario dimensions, timestamps, counts, measurements, safe event text, and safe metadata JSON.

Responses do not expose raw execution configuration, raw merge plans, API keys, password hashes, lease tokens, lease hashes, worker secrets, local filesystem paths, physical artifact paths, stack traces, or internal storage keys.

M30 does not change existing worker authentication, JWT authentication, WebSocket authentication, SOAP authentication, artifact download authorization, or execution lease behavior.

## Current Limitations

M30 intentionally does not implement:

- automatic workload execution,
- automatic execution or execution group creation,
- artifact upload/download orchestration,
- CSV/JSON export,
- frontend UI,
- live benchmark dashboard,
- runtime smoke tests.

## Future Extensions

Later milestones can build on this persistence layer to add external/runtime benchmark runners, benchmark exports, fault injection scenarios, and thesis evidence packs.
