# Research Protocol Comparison Runner

M31 adds a small admin-only runner that compares common read-only execution group operations across the current REST, WebSocket, and SOAP research adapters.

The runner is intentionally limited. It records server-side adapter/read-model measurements into the M30 benchmark tables. It is not a full external network benchmark and it does not run workloads. The combined runtime smoke remains deferred until after M33.

## Endpoint

```http
POST /api/admin/research/protocol-comparison-runs
```

Security:

- ADMIN JWT required.
- Worker API keys are rejected.
- Unauthenticated requests are rejected.
- USER role is rejected.

Request:

```json
{
  "displayName": "Read model protocol comparison",
  "description": "Compare REST, WebSocket and SOAP read operations for the same execution group.",
  "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
  "targetExecutionGroupId": "00000000-0000-0000-0000-000000000000",
  "protocols": ["REST", "WEBSOCKET", "SOAP"],
  "operations": ["GET_GROUP_DETAIL", "GET_GROUP_ACTIVITY", "GET_GROUP_ARTIFACTS"],
  "repetitions": 3,
  "tags": ["m31", "read-model"],
  "notes": "M31 runner MVP."
}
```

Response:

```json
{
  "benchmarkRunId": "00000000-0000-0000-0000-000000000000",
  "status": "COMPLETED",
  "targetExecutionGroupId": "00000000-0000-0000-0000-000000000000",
  "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
  "protocols": ["REST", "WEBSOCKET", "SOAP"],
  "operations": ["GET_GROUP_DETAIL", "GET_GROUP_ACTIVITY", "GET_GROUP_ARTIFACTS"],
  "repetitions": 3,
  "scenarioCount": 27,
  "measurementCount": 81,
  "eventCount": 166,
  "summary": [
    {
      "protocol": "REST",
      "completedScenarios": 9,
      "failedScenarios": 0,
      "skippedScenarios": 0,
      "avgRequestLatencyMs": 2.50,
      "avgPayloadResponseBytes": 1024.00,
      "errorCount": 0
    }
  ]
}
```

## Supported Operations

M31 compares only operations that are common to REST, WebSocket, and SOAP:

- `GET_GROUP_DETAIL`
- `GET_GROUP_ACTIVITY`
- `GET_GROUP_ARTIFACTS`

`CREATE_EXECUTION_GROUP`, `CREATE_SINGLE_EXECUTION`, `GET_EXECUTION_STATUS`, `STREAM_GROUP_ACTIVITY`, `STOP_STREAM_GROUP_ACTIVITY`, `DOWNLOAD_ARTIFACT`, `CANCEL_GROUP`, and `RECONCILE_GROUP` are rejected by the M31 runner request validation.

`GET_GROUP_ARTIFACTS` reads safe artifact metadata only. It does not download binary artifact content.

## Validation

Validation happens before the benchmark run is created:

- `displayName` required, nonblank, max 200.
- `description` max 2000.
- `workloadId` required and must exist in the M29 workload catalog.
- `targetExecutionGroupId` required and must point to an existing execution group.
- `protocols` required, non-empty, max 3, no duplicates.
- `operations` required, non-empty, M31 read-only operations only, no duplicates.
- `repetitions` required, min 1, max 20.
- `tags` max 20, each item nonblank, max 50.
- `notes` max 4000.
- Each protocol/operation pair is validated through the M26 protocol contract validator.
- Each workload/protocol/operation pair is validated through the M29 workload catalog validator.

Invalid request data returns `400`. Missing target execution groups return `404`.

## Runner Model

For a valid request, M31:

1. Creates a benchmark run with the M30 recorder.
2. Starts the benchmark run.
3. Creates one benchmark scenario for each protocol x operation x repetition.
4. Starts each scenario.
5. Invokes the selected read-only operation through the selected adapter model.
6. Records measurements.
7. Completes or fails the scenario.
8. Completes the benchmark run when all scenarios complete.
9. Records one safe summary note event linked to the target execution group.

If an individual adapter invocation fails, the scenario records `ERROR_COUNT = 1` and is marked failed with safe error metadata. If any scenario fails, the benchmark run is marked `FAILED`.

## Protocol Invokers

REST invoker:

- reads the same safe group DTOs used by the REST admin API,
- serializes the DTO to JSON,
- records payload size from the JSON response bytes.

WebSocket invoker:

- reads the same safe group DTOs used by the WebSocket adapter,
- wraps the data in the existing response envelope shape,
- serializes the envelope to JSON,
- records payload size from the envelope bytes.

SOAP invoker:

- sends an in-process SOAP request through the existing SOAP service,
- uses the same SOAP response rendering path as the SOAP adapter,
- records payload size from the XML response bytes.

These invokers are server-side adapter/read-model measurements. They do not open external HTTP, WebSocket, or SOAP client connections.

## Measurements

Every scenario records:

- `REQUEST_LATENCY_MS`
- `PAYLOAD_RESPONSE_BYTES`
- `ERROR_COUNT`

M31 does not record binary artifact transfer, artifact upload/download time, end-to-end workload time, time-to-final-status, throughput, retry count, or live stream timing.

## Events

M31 reuses M30 recorder events:

- `RUN_CREATED`
- `RUN_STARTED`
- `SCENARIO_CREATED`
- `SCENARIO_STARTED`
- `MEASUREMENT_RECORDED`
- `SCENARIO_COMPLETED`
- `SCENARIO_FAILED`
- `RUN_COMPLETED`
- `RUN_FAILED`
- `NOTE_RECORDED`

Events contain safe benchmark metadata only.

## Behavior Safety

M31 does not:

- create `WorkExecution`,
- create `ExecutionGroup`,
- create `ExecutionAssignment`,
- create `Artifact`,
- upload workspace artifacts,
- upload output artifacts,
- download binary artifact content,
- call worker claim/report endpoints,
- schedule workers,
- cancel execution groups,
- reconcile execution groups,
- mutate the target execution group.

The only persistence changes are benchmark run, scenario, measurement, and event rows in the M30 tables.

## Security

The response and recorded benchmark metadata do not expose:

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

The target execution group id and safe read-model DTO fields are allowed.

## Current Limitations

M31 does not implement:

- full external network benchmarking,
- runtime workload execution,
- automatic execution group creation,
- artifact binary transfer comparison,
- WebSocket streaming comparison,
- SOAP streaming,
- output artifact download benchmarking,
- benchmark export,
- frontend UI,
- fault injection.

## Roadmap Relation

M32 can export benchmark data produced by M30/M31. M33 can add fault injection and robustness scenarios. M43 can use post-M33 runtime smoke and final research runs as thesis evidence.
