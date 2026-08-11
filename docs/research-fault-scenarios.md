# Research Fault Scenarios

M33 adds a static, read-only fault scenario catalog for later robustness and thesis evidence work. The catalog describes failure and protocol-negative cases that can be paired with the M26 protocol contract and M29 workload catalog.

The M33 endpoints do not run workloads, create executions, create execution groups, cancel groups, reconcile groups, upload artifacts, download artifacts, pause workers, or mutate runtime state. They only expose safe metadata and validate whether a selected scenario is meaningful for a selected protocol and workload combination.

## Purpose

The fault scenario catalog gives research tooling a stable vocabulary for:

- worker disconnect and timeout observations,
- controlled task and merge failures,
- group cancellation behavior,
- invalid REST payload handling,
- malformed SOAP request handling,
- broken WebSocket stream handling,
- intentionally unsupported protocol combinations.

## Endpoints

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

### List Catalog

```http
GET /api/admin/research/fault-scenarios
```

Response shape:

```json
{
  "generatedAt": "2026-08-11T12:45:00",
  "scenarios": [
    {
      "id": "TASK_FAILURE_EXIT_CODE",
      "name": "Task failure exit code",
      "type": "TASK_FAILURE",
      "severity": "MEDIUM",
      "injectionMode": "WORKLOAD_LEVEL",
      "expectedSystemBehavior": "SAFE_FAILURE_STATUS",
      "recommendedProtocols": ["REST"],
      "recommendedOperations": ["CREATE_SINGLE_EXECUTION", "GET_EXECUTION_STATUS"],
      "compatibleWorkloadTypes": ["FAILING_TASK"],
      "compatibleExecutionShapes": ["SINGLE_EXECUTION"],
      "description": "Controlled failing single execution with safe failure metadata.",
      "researchPurpose": "Checks failed status propagation and safe error reporting.",
      "requiresExistingExecutionGroup": false,
      "requiresRunningWorker": true,
      "requiresDocker": true,
      "requiresManualAction": false,
      "tags": ["failure", "single", "exit-code"]
    }
  ]
}
```

### Get Scenario

```http
GET /api/admin/research/fault-scenarios/{scenarioId}
```

Behavior:

- known scenario id returns `200`,
- unknown scenario id returns `404`.

### Validate Scenario Combination

```http
POST /api/admin/research/fault-scenarios/validate
```

Request:

```json
{
  "scenarioId": "MALFORMED_SOAP_REQUEST",
  "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
  "protocol": "SOAP",
  "operation": "GET_GROUP_DETAIL",
  "dataTransferMode": "INLINE_XML",
  "payloadFormat": "XML"
}
```

Supported response:

```json
{
  "valid": true,
  "reasonCode": "SUPPORTED",
  "reasonMessage": "Fault scenario can be used with the selected workload and protocol combination."
}
```

Unsupported response:

```json
{
  "valid": false,
  "reasonCode": "FAULT_REQUIRES_SOAP",
  "reasonMessage": "Fault scenario requires SOAP."
}
```

Validation behavior:

- `scenarioId` must exist in the M33 catalog,
- `workloadId` must exist in the M29 workload catalog,
- scenario-specific protocol requirements are checked for REST, SOAP, and WebSocket-only cases,
- regular scenarios delegate to the M26 protocol validator and then to the M29 workload validator,
- scenario-specific workload rules are checked after the protocol and workload combination is supported,
- `UNSUPPORTED_PROTOCOL_COMBINATION` intentionally accepts unsupported protocol combinations as a valid negative-case scenario,
- non-recommended combinations are not rejected unless they are impossible for the selected fault scenario or workload.

Malformed JSON, invalid enum values, or missing required request fields return `400` through the existing error handling path. Known but unsupported combinations return `200` with `valid = false`.

## Reason Codes

Stable M33 validation reason codes:

- `SUPPORTED`,
- `UNKNOWN_FAULT_SCENARIO`,
- `UNKNOWN_WORKLOAD`,
- `PROTOCOL_COMBINATION_NOT_SUPPORTED`,
- `WORKLOAD_COMBINATION_NOT_SUPPORTED`,
- `FAULT_REQUIRES_REST`,
- `FAULT_REQUIRES_WEBSOCKET`,
- `FAULT_REQUIRES_SOAP`,
- `FAULT_REQUIRES_STREAMING`,
- `FAULT_REQUIRES_GROUP_OPERATION`,
- `FAULT_REQUIRES_RUNNING_WORKER`,
- `FAULT_REQUIRES_DOCKER`,
- `FAULT_REQUIRES_MANUAL_ACTION`,
- `FAULT_NOT_COMPATIBLE_WITH_OPERATION`,
- `FAULT_NOT_COMPATIBLE_WITH_WORKLOAD`,
- `COMBINATION_NOT_RECOMMENDED`.

The `UNKNOWN_*` reason codes are part of the validation model. Public JSON enum binding can still reject unknown enum text with `400` before the validator runs.

## Scenario Types

Supported M33 scenario types:

- `WORKER_OFFLINE`,
- `TASK_FAILURE`,
- `MERGE_FAILURE`,
- `GROUP_CANCELLED`,
- `INVALID_PAYLOAD`,
- `MALFORMED_SOAP`,
- `BROKEN_WEBSOCKET_STREAM`,
- `UNSUPPORTED_PROTOCOL_COMBINATION`,
- `TIMEOUT`.

Severity values are `LOW`, `MEDIUM`, and `HIGH`.

Expected system behavior values:

- `SAFE_ERROR`,
- `SAFE_FAILURE_STATUS`,
- `SAFE_CANCELLATION`,
- `SAFE_TIMEOUT`,
- `SAFE_REJECTION`,
- `CLEAN_DISCONNECT`,
- `NO_RUNTIME_MUTATION`.

Injection mode values:

- `MANUAL`,
- `REQUEST_LEVEL`,
- `STREAM_LEVEL`,
- `WORKLOAD_LEVEL`,
- `ENVIRONMENT_LEVEL`.

## Catalog Entries

| ID | Type | Severity | Expected behavior | Main workload fit |
| --- | --- | --- | --- | --- |
| `WORKER_OFFLINE_DURING_EXECUTION` | `WORKER_OFFLINE` | `HIGH` | `SAFE_TIMEOUT` | grouped workloads |
| `TASK_FAILURE_EXIT_CODE` | `TASK_FAILURE` | `MEDIUM` | `SAFE_FAILURE_STATUS` | `FAILING_TASK_SINGLE` |
| `MERGE_FAILURE_AGENT` | `MERGE_FAILURE` | `HIGH` | `SAFE_FAILURE_STATUS` | `AGENT_MERGE_OPTIMIZATION_4` |
| `CANCELLED_GROUP_QUEUED` | `GROUP_CANCELLED` | `MEDIUM` | `SAFE_CANCELLATION` | `CANCELLED_GROUP_QUEUED` |
| `INVALID_REST_PAYLOAD` | `INVALID_PAYLOAD` | `LOW` | `SAFE_REJECTION` | REST create operations |
| `MALFORMED_SOAP_REQUEST` | `MALFORMED_SOAP` | `LOW` | `SAFE_ERROR` | SOAP group operations |
| `BROKEN_WEBSOCKET_STREAM` | `BROKEN_WEBSOCKET_STREAM` | `MEDIUM` | `CLEAN_DISCONNECT` | WebSocket group activity stream |
| `UNSUPPORTED_PROTOCOL_COMBINATION` | `UNSUPPORTED_PROTOCOL_COMBINATION` | `LOW` | `SAFE_REJECTION` | intentionally unsupported protocol tuple |
| `LONG_RUNNING_TIMEOUT` | `TIMEOUT` | `MEDIUM` | `SAFE_TIMEOUT` | `LONG_RUNNING_SINGLE` |

## Security

Responses expose only safe research metadata: ids, names, enum values, descriptions, purposes, boolean requirements, tags, validation status, and validation reason metadata.

Responses do not expose raw executor configuration, raw merge plans, API keys, password hashes, lease tokens, lease hashes, worker secrets, local filesystem paths, physical artifact paths, stack traces, or internal storage keys.

M33 does not change existing worker authentication, JWT authentication, WebSocket authentication, SOAP authentication, artifact authorization, worker pause behavior, execution leases, scheduling, cancellation, reconciliation, or artifact storage.

## Current Limitations

M33 intentionally does not implement:

- runtime fault injection,
- automatic worker shutdown or network disruption,
- synthetic Docker failures,
- automatic malformed SOAP request execution,
- automatic WebSocket disconnect runner,
- benchmark result recording,
- result export changes,
- frontend UI,
- scheduling changes,
- worker protocol changes.

## Future Extensions

Future milestones can use this catalog from the protocol comparison runner, benchmark recorder, or thesis evidence workflow to drive controlled runtime experiments. Those extensions should keep the catalog validation boundary separate from execution mutation.
