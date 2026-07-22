# Selection Diagnostics API

M13.1 adds a read-only admin diagnostics endpoint for capability-aware worker selection.

The endpoint helps explain why workers match or do not match a planned `AUTO`, `PREFER`, or `REQUIRE` execution request. It is intended for debugging M13 worker selection decisions before creating an execution.

Diagnostics does not create executions and does not modify worker state.

## Endpoint

```http
POST /api/admin/executions/selection-diagnostics
Authorization: Bearer <ADMIN_JWT>
Content-Type: application/json
Accept: application/json
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

## Purpose

Use this endpoint when an admin client needs to understand selection behavior before calling:

```http
POST /api/admin/executions
```

Diagnostics:

- validates and plans the request consistently with the admin create execution path,
- evaluates worker eligibility for the requested assignment mode,
- returns per-worker status, resource, capability, eligibility, and rejection metadata,
- predicts the selected worker for `AUTO` and `PREFER` when state does not change,
- returns `200 OK` when no worker is eligible for `AUTO` or `PREFER`.

Actual create execution may still return `409 Conflict` when no eligible worker is available.

## Request Shape

The request shape matches `POST /api/admin/executions`.

Example Docker diagnostics request:

```json
{
  "workDefinitionVersionId": "00000000-0000-0000-0000-000000000000",
  "workerId": null,
  "assignmentMode": "AUTO",
  "displayName": "optional diagnostic name",
  "configuration": {
    "image": "alpine:3.20",
    "command": ["sh", "-c", "echo diagnostics"],
    "timeoutSeconds": 30,
    "resources": {
      "memoryMb": 128,
      "cpuCores": 1
    },
    "gpu": {
      "required": false
    }
  }
}
```

Fields:

| Field | Description |
| --- | --- |
| `workDefinitionVersionId` | UUID of the Work Definition Version to diagnose. |
| `workerId` | Mode-dependent worker UUID. Required for `REQUIRE` and `PREFER`; absent or `null` for `AUTO`. |
| `assignmentMode` | `REQUIRE`, `AUTO`, or `PREFER`. Defaults follow the create execution API. |
| `displayName` | Optional. Validated consistently with create execution, but not persisted by diagnostics. |
| `configuration` | Executor-dependent configuration validated like create execution. |

Invalid definition IDs, unsupported executor versions, invalid configuration, invalid assignment modes, and invalid worker mode combinations follow the existing admin API error conventions.

## Response Shape

Example response:

```json
{
  "workDefinitionVersionId": "00000000-0000-0000-0000-000000000000",
  "logicalId": "localhive.docker.workload",
  "version": 1,
  "executorId": "localhive.docker.workload",
  "executorContractVersion": 1,
  "assignmentMode": "AUTO",
  "eligibleWorkerCount": 1,
  "selectedWorkerId": "11111111-1111-1111-1111-111111111111",
  "workers": [
    {
      "workerId": "11111111-1111-1111-1111-111111111111",
      "hostname": "AMG-NORTH",
      "status": {
        "approval": "APPROVED",
        "connection": "ONLINE",
        "availability": "AVAILABLE"
      },
      "resources": {
        "sharedRamMb": 5632,
        "cpuCores": 32
      },
      "capabilities": {
        "reported": true,
        "executorMatched": true,
        "dockerReported": true,
        "dockerEnabled": true,
        "imageAllowed": true,
        "policyMemoryFits": true,
        "policyCpuFits": true
      },
      "eligible": true,
      "selected": true,
      "rejectionReasons": []
    }
  ]
}
```

Response fields:

| Field | Description |
| --- | --- |
| `workDefinitionVersionId` | Diagnosed Work Definition Version UUID. |
| `logicalId` | Work Definition logical identifier. |
| `version` | Work Definition Version number. |
| `executorId` | Executor ID used by the version. |
| `executorContractVersion` | Executor contract version. |
| `assignmentMode` | Requested assignment mode. |
| `eligibleWorkerCount` | Number of eligible workers in the diagnostic result. |
| `selectedWorkerId` | Predicted selected worker UUID, or `null` when none is selected. |
| `workers` | Per-worker diagnostic result. |

The response is a safe summary. It does not include raw configuration, raw capability JSON, configuration snapshots, secrets, lease fields, or physical paths.

## Assignment Mode Diagnostics

### REQUIRE

`REQUIRE` is manual diagnostics for one requested worker.

Rules:

- `workerId` is required.
- The worker must exist.
- The worker must be `APPROVED`.
- Diagnostics returns only the requested worker.
- The selected worker is the requested worker when the create path would allow it.
- Capability flags may be visible.
- Missing, mismatched, or disabled capabilities do not become rejection reasons.
- Resource fit and capability fit do not block `REQUIRE` diagnostics.

This matches `REQUIRE` as a manual approved-worker assignment path.

### AUTO

`AUTO` evaluates known workers and predicts automatic selection.

Rules:

- `workerId` must be absent or `null`.
- Diagnostics evaluates registered workers and returns per-worker results.
- The endpoint uses M13 eligibility logic.
- `selectedWorkerId` predicts the worker actual create execution would select if state does not change.
- If no worker is eligible, diagnostics returns `200 OK`, `selectedWorkerId: null`, and `eligibleWorkerCount: 0`.

### PREFER

`PREFER` evaluates the preferred worker and possible fallback candidates.

Rules:

- `workerId` is required.
- The preferred worker must exist.
- If the preferred worker is eligible, `selectedWorkerId` is the preferred worker.
- If the preferred worker is ineligible, a fallback eligible worker can be selected.
- If no fallback is eligible, diagnostics returns `200 OK` with `selectedWorkerId: null`.
- Unknown preferred workers follow the existing not-found behavior.
- `assignmentMode` remains `PREFER`.

## Rejection Reasons

Diagnostics can return these stable reason codes:

```text
WORKER_NOT_APPROVED
WORKER_OFFLINE
WORKER_NOT_AVAILABLE
WORKER_HAS_ACTIVE_EXECUTION
WORKER_MEMORY_TOO_LOW
WORKER_CPU_TOO_LOW
MISSING_CAPABILITIES
EXECUTOR_NOT_SUPPORTED
EXECUTOR_DISABLED
DOCKER_CAPABILITY_MISSING
DOCKER_DISABLED
DOCKER_IMAGE_NOT_ALLOWED
DOCKER_POLICY_MEMORY_EXCEEDED
DOCKER_POLICY_CPU_EXCEEDED
GPU_UNSUPPORTED
```

Notes:

- These codes are admin diagnostics metadata, not a public scheduler policy.
- `AUTO` and `PREFER` include capability blockers.
- `REQUIRE` does not use capability blockers as rejection reasons.

## Consistency With Selection

Diagnostics uses the same validation, planning, and eligibility logic as admin create execution.

For `AUTO` and `PREFER`, `selectedWorkerId` should match actual create execution selection if worker state, assignments, and capability snapshots do not change between the diagnostics call and the create call.

Selection order remains:

1. highest memory headroom,
2. highest CPU headroom,
3. least recently assigned worker,
4. `workerId` ascending.

`AUTO` and `PREFER` with no eligible worker return `200 OK` from diagnostics. Actual create execution can return `409 Conflict` because it is attempting to create and assign real work.

## No Side Effects

The diagnostics endpoint is read-only.

It does not create:

- `WorkExecution`,
- `WorkInstance`,
- `ExecutionAssignment`,
- artifacts.

It does not modify:

- worker approval, connection, or availability state,
- worker heartbeat state,
- worker resource fields,
- capability snapshots,
- storage or artifact state.

## Security / Non-Exposed Fields

Diagnostics does not expose:

- raw config,
- `requestedConfigurationSnapshot`,
- `resolvedConfigurationSnapshot`,
- `executorConfiguration`,
- raw full capabilities JSON,
- API key,
- API key hash,
- password hash,
- lease token,
- lease token hash,
- `leaseExpiresAt`,
- `storagePath`,
- `dataRoot`,
- physical paths,
- stack traces,
- internal exception details.

## Current Limitations

- Diagnostics currently targets admin/local scale.
- The endpoint may inspect all registered workers.
- Large clusters may need pagination or explicit limits later.
- No frontend UI exists yet.
- No live Docker health check is performed.
- No capability TTL exists.
- No GPU support exists.
- No background scheduler exists.

## Relation To Other Admin APIs

| Endpoint | Purpose |
| --- | --- |
| `POST /api/admin/executions/selection-diagnostics` | Explain worker selection before creating execution. |
| `POST /api/admin/executions` | Create one execution and assignment. |
| `GET /api/admin/workers/{workerId}` | Inspect one worker and its latest capabilities. |
| `GET /api/admin/executions` | Monitor execution history after creation. |
| `GET /api/admin/executions/{executionId}` | Inspect one created execution. |

Use diagnostics as a troubleshooting tool before create execution. Use execution APIs after work has been created.

## Future Extensions

Future work may add:

- frontend diagnostics UI,
- paginated diagnostics for larger clusters,
- richer capability mismatch explanations,
- capability freshness rules,
- live Docker health checks,
- scheduler dry-run diagnostics,
- GPU-aware diagnostics after explicit GPU support is designed.
