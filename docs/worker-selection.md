# Worker Selection

M11 extends `POST /api/admin/executions` with worker assignment modes. M13 makes `AUTO` and `PREFER` capability-aware by using the latest Agent capability snapshot stored by M12.

Worker selection happens during the create execution request. Master selects at most one worker for one execution. This is not a background scheduler, periodic assignment process, queue worker, or multi-worker distributed execution. The Agent still receives work by claiming assigned executions through the existing worker API.

Use [Selection Diagnostics API](selection-diagnostics-api.md) to inspect why current workers match or do not match a planned `AUTO`, `PREFER`, or `REQUIRE` request before creating an execution.

## Assignment Modes

| Mode | Meaning |
| --- | --- |
| `REQUIRE` | Admin explicitly selects the worker. |
| `AUTO` | Master selects one eligible worker. |
| `PREFER` | Master tries the preferred worker first, then falls back to `AUTO`. |

## REQUIRE

`REQUIRE` is a manual admin decision.

Rules:

- `workerId` is required.
- The worker must exist.
- The worker must be `APPROVED`.
- `ONLINE` is not required at creation time.
- `AVAILABLE` is not required at creation time.
- An active execution does not block creation-time assignment.
- Capabilities are not required.
- Missing, mismatched, or disabled capabilities do not block `REQUIRE`.
- No worker state is mutated.
- No API key is created, returned, rotated, or regenerated.

The assignment may wait until the worker can claim it. Existing worker claim rules still decide when the Agent can take the execution. `REQUIRE` is the manual override and legacy fallback path for approved workers.

## AUTO

`AUTO` asks Master to choose one worker at create time.

Rules:

- `workerId` must be absent or `null`.
- Master selects one worker.
- The selected worker must be eligible at selection time.
- The selected worker must pass capability fit.
- A worker without capabilities is ineligible.
- No eligible worker returns `409 Conflict`.
- The response `assignment.workerId` contains the actual selected worker.
- The response `assignment.mode` is `AUTO`.

Eligibility for `AUTO`:

- `approvalStatus == APPROVED`
- `connectionStatus == ONLINE`
- `availabilityStatus == AVAILABLE`
- no active execution
- resource fit passes
- capability fit passes

Active execution statuses are:

- `ASSIGNED`
- `CLAIMED`
- `RUNNING`

Terminal or completed statuses do not block selection.

## PREFER

`PREFER` asks Master to try one worker first and fall back when that worker is not currently eligible.

Rules:

- `workerId` is required.
- The preferred worker must exist.
- If the preferred worker is eligible, including capability fit, it is selected.
- If the preferred worker exists but is not eligible, Master falls back to `AUTO`.
- If no eligible fallback worker exists, the request returns `409 Conflict`.
- The response shows the actual selected worker.
- The response `assignment.mode` remains `PREFER` as requested.

An unknown preferred worker returns the current not-found behavior. The preferred worker uses the same eligibility rules as `AUTO`, including capability fit.

## Eligibility Rules

Eligibility applies only to `AUTO` and `PREFER`. `REQUIRE` uses explicit admin assignment and keeps the M10 approved-only behavior.

| Check | REQUIRE | AUTO | PREFER |
| --- | ---: | ---: | ---: |
| Worker must exist | yes | selected by Master | preferred must exist |
| `APPROVED` | yes | yes | yes |
| `ONLINE` | no | yes | yes |
| `AVAILABLE` | no | yes | yes |
| No active execution | no | yes | yes |
| Resource fit | no | yes | yes |
| Capability fit | no | yes | yes |

## Resource Fit

### localhive.no-op

`localhive.no-op` has no meaningful resource requirement and always fits resources.

### localhive.docker.workload

`localhive.docker.workload` uses the already validated Docker configuration.

Rules:

- requested `resources.memoryMb <= worker.sharedRamMb`
- requested `resources.cpuCores <= worker.cpuCores`
- `gpu.required` must be `false`

Notes:

- memory fit uses `sharedRamMb`, not `totalRamMb`,
- `sharedRamMb == null` or `sharedRamMb == 0` does not fit Docker,
- CPU must be sufficient,
- GPU remains unsupported,
- Master does not sync or control full Agent local Docker policy beyond the latest reported capability snapshot,
- Agent remains final enforcement for local Docker policy.

## Capability Fit

Capability fit applies only to `AUTO` and `PREFER`. It is an eligibility filter, not a scoring factor.

Master uses the latest capability snapshot stored in `worker_capabilities`. No capability TTL or staleness rule is added in M13; worker online/offline state remains the heartbeat freshness signal.

General executor fit requires:

- matching `executorId`,
- matching `executorContractVersion`,
- `enabled == true`.

Unsupported executors are rejected earlier by Admin Create Execution validation before worker selection runs.

### localhive.no-op Capability Fit

`localhive.no-op` requires an enabled `localhive.no-op` executor capability with contract version `1`.

No additional policy check is applied.

### localhive.docker.workload Capability Fit

`localhive.docker.workload` requires:

- an enabled `localhive.docker.workload` executor capability with contract version `1`,
- Docker summary present,
- `docker.enabled == true`,
- requested image present in `docker.allowedImages`,
- requested memory less than or equal to `docker.maxMemoryMb` when `maxMemoryMb` is present,
- requested CPU less than or equal to `docker.maxCpuCores` when `maxCpuCores` is present.

Rules:

- missing or empty `docker.allowedImages` means no Docker image fits,
- `docker.maxMemoryMb == null` does not block by itself,
- `docker.maxCpuCores == null` does not block by itself,
- hardware resource fit still applies independently through `worker.sharedRamMb` and `worker.cpuCores`,
- GPU remains unsupported,
- the Agent remains the final runtime enforcement point for local Docker policy.

## Missing Capabilities

For `AUTO` and `PREFER`, a worker without a capability snapshot is ineligible. Master does not guess executor or Docker policy support during automatic selection.

For `REQUIRE`, missing capabilities are allowed for an `APPROVED` worker.

Older Agents can still heartbeat with the existing heartbeat shape. They will not be selected automatically by `AUTO` or `PREFER` until they report capabilities, unless an admin uses `REQUIRE`.

## Scoring

After eligibility filtering, worker choice is deterministic.

Selection order:

1. highest memory headroom
2. highest CPU headroom
3. least recently assigned worker
4. `workerId` ascending

Definitions:

```text
memoryHeadroomMb = worker.sharedRamMb - requestedMemoryMb
cpuHeadroom = worker.cpuCores - requestedCpuCores
```

A worker with no previous assignment is preferred over workers with assignment history. If both workers have assignment history, the worker with the older latest assignment wins. The final tie-breaker is `workerId` ascending. There is no random selection.

## Examples

### AUTO Docker Request

```http
POST /api/admin/executions
Authorization: Bearer {{auth_token}}
Content-Type: application/json

{
  "workDefinitionVersionId": "{{docker_definition_version_uuid}}",
  "assignmentMode": "AUTO",
  "displayName": "AUTO Docker Job",
  "configuration": {
    "image": "alpine:3.20",
    "command": [
      "sh",
      "-c",
      "mkdir -p /output && echo auto > /output/auto.txt"
    ],
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

### PREFER Docker Request

```http
POST /api/admin/executions
Authorization: Bearer {{auth_token}}
Content-Type: application/json

{
  "workDefinitionVersionId": "{{docker_definition_version_uuid}}",
  "workerId": "{{preferred_worker_uuid}}",
  "assignmentMode": "PREFER",
  "displayName": "Preferred Docker Job",
  "configuration": {
    "image": "alpine:3.20",
    "command": [
      "sh",
      "-c",
      "mkdir -p /output && echo prefer > /output/prefer.txt"
    ],
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

## Error Behavior

| Case | Result |
| --- | --- |
| `AUTO` with `workerId` | `400 Bad Request` |
| `AUTO` without eligible worker | `409 Conflict` |
| `PREFER` without `workerId` | `400 Bad Request` |
| `PREFER` with unknown worker | not-found behavior |
| `PREFER` with ineligible preferred worker and no fallback | `409 Conflict` |

`409 Conflict` means the request is structurally valid, but current worker state cannot satisfy assignment. Worker state can change when Agents come online, resume, or finish active executions.

## Response Behavior

The create response remains a safe summary. It shows the actual selected worker in `assignment.workerId` and does not expose raw config, secrets, lease fields, or physical paths.

Example fragment:

```json
{
  "status": "ASSIGNED",
  "assignment": {
    "workerId": "00000000-0000-0000-0000-000000000000",
    "workerHostname": "Worker-B",
    "mode": "AUTO",
    "assignedAt": "2026-07-20T10:00:00"
  }
}
```

For `PREFER`, the response shows the actual selected worker while `assignment.mode` remains `PREFER`.

## Security / Non-Exposed Fields

Worker selection documentation and the admin create response do not expose:

- API keys,
- API key hashes,
- password hashes,
- raw capability payloads,
- lease tokens,
- lease token hashes,
- `leaseExpiresAt`,
- raw `requestedConfigurationSnapshot`,
- raw `resolvedConfigurationSnapshot`,
- `executorConfiguration`,
- `storagePath`,
- `dataRoot`,
- physical absolute storage paths,
- stack traces,
- internal exception details.

Full request configuration and full capability payloads should not be expected in worker selection logs.

## Relation To Other Admin APIs

| Endpoint | Purpose |
| --- | --- |
| `GET /api/admin/work-definitions` | Choose a Work Definition and version. |
| `POST /api/admin/executions` | Create one execution and assign it using `REQUIRE`, `AUTO`, or `PREFER`. |
| `POST /api/admin/executions/selection-diagnostics` | Read-only diagnostics for worker selection decisions. |
| `GET /api/admin/executions` | Monitor execution history. |
| `GET /api/admin/executions/{executionId}` | Inspect one execution. |
| `GET /api/admin/workers` | Inspect worker overview state. |
| `GET /api/admin/workers/{workerId}` | Inspect status and resource fields used by `AUTO` and `PREFER`. |

The Work Definition API helps choose a definition/version. The Admin Worker API helps inspect workers. The Create Execution API uses assignment modes. This document describes how `AUTO` and `PREFER` choose a worker. The Admin Execution API monitors the result.

## Current Limitations

- no background scheduler,
- no periodic assignment process,
- selection happens only during `POST /api/admin/executions`,
- one selected worker per execution,
- no multi-worker or distributed execution,
- no parent or child executions,
- no retry, requeue, or cancel,
- no workload lifecycle support,
- no GPU support,
- no Agent policy sync,
- no capability TTL or capability history scoring,
- no historical performance scoring,
- no frontend UI yet.

## Future Extensions

Future work may add:

- real scheduler loop,
- queueing without immediate assignment,
- `AUTO` and `PREFER` with a resource-aware queue,
- richer worker capability diagnostics,
- GPU-aware selection,
- load, history, or performance-based scoring,
- cancellation, retry, and requeue,
- workload lifecycle assignment,
- distributed or multi-worker execution,
- frontend create execution wizard.
