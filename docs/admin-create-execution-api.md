# Admin Create Execution API

M10 added a production admin endpoint for creating one-off executions without using dev-smoke endpoints. M11 extends that endpoint with worker selection modes, and M13 makes `AUTO` and `PREFER` capability-aware.

Before M10, real smoke executions were created through dev-only smoke helpers. M9 added read-only admin visibility into Work Definitions and their versions. M10 completed the first production admin path: an admin can select an approved Work Definition Version, create a `WorkExecution`, and create an `ExecutionAssignment`. M11 adds `REQUIRE`, `AUTO`, and `PREFER` assignment modes for choosing the worker during that create request. M13 extends automatic selection with Agent capability snapshots.

The Agent does not receive work through this admin endpoint directly. It continues to claim assigned executions through the existing worker claim API. Dev-smoke endpoints still exist for local testing, but they are not the target path for a future Master frontend.

Use [Selection Diagnostics API](selection-diagnostics-api.md) before creating an execution when an admin client needs to see why `AUTO`, `PREFER`, or `REQUIRE` would select or reject current workers.

## Endpoint

```http
POST /api/admin/executions
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Behavior:

- creates a one-off `WorkExecution`,
- creates one `ExecutionAssignment`,
- supports `REQUIRE`, `AUTO`, and `PREFER` assignment modes,
- returns `201 Created`,
- created execution starts as `ASSIGNED`,
- Agent can claim it through the existing worker claim API,
- worker selection happens only during this request,
- no background scheduler,
- no retry or requeue endpoint,
- cancellation is handled by the separate [Admin Execution Cancel API](admin-execution-cancel-api.md),
- no multi-worker execution.

## Request Shape

Example Docker request:

```json
{
  "workDefinitionVersionId": "00000000-0000-0000-0000-000000000000",
  "workerId": "00000000-0000-0000-0000-000000000000",
  "assignmentMode": "REQUIRE",
  "displayName": "M10 Docker Smoke",
  "configuration": {
    "image": "alpine:3.20",
    "command": [
      "sh",
      "-c",
      "mkdir -p /output && echo m10-admin-create > /output/m10.txt"
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

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `workDefinitionVersionId` | yes | UUID of the Work Definition Version to execute. |
| `workerId` | mode-dependent | UUID of the explicit or preferred worker. Required for `REQUIRE` and `PREFER`; absent for `AUTO`. |
| `assignmentMode` | no | Defaults to `REQUIRE`. Supported values are `REQUIRE`, `AUTO`, and `PREFER`. |
| `displayName` | no | Optional human-readable name for the execution snapshot. |
| `configuration` | executor-dependent | JSON object validated by the selected executor rules. |

Rules:

- `AUTO` with `workerId` returns `400`.
- `PREFER` without `workerId` returns `400`.
- `AUTO` or `PREFER` without an eligible worker returns `409`.
- Blank or null `displayName` uses fallback naming.
- `displayName` is limited to 255 characters.
- `configuration` is validated per executor.
- The full raw configuration is not returned in the create response.

See [Worker Selection](worker-selection.md) for assignment mode eligibility, resource fit, capability fit, and deterministic scoring.

## Response Shape

Example response:

```json
{
  "executionId": "00000000-0000-0000-0000-000000000000",
  "displayName": "M10 Docker Smoke",
  "status": "ASSIGNED",
  "workDefinitionVersionId": "00000000-0000-0000-0000-000000000000",
  "workDefinitionLogicalId": "localhive.docker.workload",
  "workDefinitionVersion": 1,
  "executorId": "localhive.docker.workload",
  "executorContractVersion": 1,
  "assignment": {
    "workerId": "00000000-0000-0000-0000-000000000000",
    "workerHostname": "Adrian-PC",
    "mode": "REQUIRE",
    "assignedAt": "2026-07-20T10:00:00"
  },
  "createdAt": "2026-07-20T10:00:00"
}
```

The response is intentionally a safe summary. It does not include raw configuration snapshots, executor configuration, lease fields, API keys, hashes, or storage paths.

## WorkDefinitionVersion Validation

`workDefinitionVersionId` must reference an existing version.

M10 allows execution creation only when:

- the Work Definition Version is `APPROVED`,
- the Work Definition type is `TASK`,
- the executor is supported by this endpoint.

Supported executors:

- `localhive.no-op`
- `localhive.docker.workload`

Unsupported executors, unapproved versions, rejected versions, and `WORKLOAD` definitions are rejected. The Workload lifecycle is not implemented in M10.

This endpoint does not create, update, delete, import, enable, disable, or schedule Work Definitions.

## Worker Validation

Worker validation depends on `assignmentMode`.

For `REQUIRE`, `workerId` must reference an existing `APPROVED` worker. The worker does not need to be `ONLINE` or `AVAILABLE` at creation time. Assigning to an offline or paused approved worker creates the assignment, and the existing worker claim rules decide when the Agent can claim it.

For `AUTO` and `PREFER`, Master applies worker selection eligibility, resource fit, and capability fit rules. Workers without capabilities are ineligible for automatic selection. See [Worker Selection](worker-selection.md).

The endpoint does not mutate worker state and does not create, return, rotate, or regenerate API keys.

## Assignment Behavior

The endpoint creates one assignment using the requested mode:

- `REQUIRE` assigns to the explicitly selected approved worker.
- `AUTO` selects one currently eligible worker, including capability eligibility.
- `PREFER` tries the preferred worker first and falls back to `AUTO` when the preferred worker is ineligible, including capability mismatch or missing capabilities.

The response contains the actual selected worker in `assignment.workerId`. For `PREFER`, `assignment.mode` remains `PREFER` even when Master selected a fallback worker.

This endpoint still does not add:

- background scheduler behavior,
- multi-worker parent or child executions,
- retry,
- requeue.

Executions that have not started real Agent-side execution can be cancelled through the separate [Admin Execution Cancel API](admin-execution-cancel-api.md).

## Supported Executor Configurations

### localhive.no-op

NO_OP configuration can be an empty object. It may also contain an optional string `message`.

Unexpected fields or a non-string `message` are rejected. The raw configuration is not returned in the admin create response.

Example:

```json
{
  "workDefinitionVersionId": "{{noop_definition_version_uuid}}",
  "workerId": "{{worker_uuid}}",
  "assignmentMode": "REQUIRE",
  "displayName": "M10 NO-OP",
  "configuration": {
    "message": "hello"
  }
}
```

### localhive.docker.workload

Docker configuration is validated by Master before the execution is created.

Rules:

- `image` is required,
- allowed image in M10 Master validation: `alpine:3.20`,
- `command` is required and must be a non-empty list,
- command entries cannot be blank,
- `timeoutSeconds` must be between `1` and `300`,
- `resources.memoryMb` must be between `16` and `4096`,
- `resources.cpuCores` must be between `1` and `8`,
- `gpu.required` must be `false`,
- GPU execution is not supported in M10,
- arbitrary mounts, environment variables, network flags, and other Docker runtime flags are not part of the M10 request shape.

Example:

```json
{
  "workDefinitionVersionId": "{{docker_definition_version_uuid}}",
  "workerId": "{{worker_uuid}}",
  "assignmentMode": "REQUIRE",
  "displayName": "M10 Docker Smoke",
  "configuration": {
    "image": "alpine:3.20",
    "command": ["sh", "-c", "echo hello from LocalHive"],
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

## Workspace Artifact Reference

Docker configuration may include a workspace reference:

```json
"workspace": {
  "artifactId": "00000000-0000-0000-0000-000000000000",
  "mountPath": "/workspace",
  "readOnly": true
}
```

Rules:

- `workspace.artifactId` must reference an existing `WORKSPACE_PACKAGE` artifact,
- `workspace.mountPath` must be exactly `/workspace`,
- `workspace.readOnly` must be `true`,
- missing or wrong-kind artifacts return a validation error.

M10 does not download, inspect, unpack, or scan the ZIP on Master during execution creation. The Agent handles download, safe unpack, and read-only mount through the existing workspace flow after it claims the execution.

Physical artifact paths are not exposed.

## Display Name Behavior

An explicit nonblank `displayName` is trimmed and stored as `displayNameSnapshot`.

Rules:

- maximum length: 255 characters,
- too long values return `400`,
- blank or null values use fallback naming,
- NO_OP fallback is currently `NO-OP smoke test`,
- Docker fallback is `Docker workload: <image>`.

The NO_OP fallback name is reused from the existing display-name logic and may be renamed later.

`displayName` does not affect execution logic and is not used as a path, shell argument, filename, authorization input, storage key, or Docker runtime argument.

## Safe Response / Non-Exposed Fields

The create response does not expose:

- API keys,
- API key hashes,
- password hashes,
- lease tokens,
- lease token hashes,
- `leaseExpiresAt`,
- raw `requestedConfigurationSnapshot`,
- raw `resolvedConfigurationSnapshot`,
- `executorConfiguration`,
- raw capability payloads,
- `storagePath`,
- `dataRoot`,
- physical absolute storage paths,
- stack traces,
- internal exception details.

The submitted configuration is stored internally for execution claim where needed, but it is not returned by the create response.

## Relation To Other Admin APIs

| Endpoint | Purpose |
| --- | --- |
| `GET /api/admin/work-definitions` | Browse Work Definitions and latest version metadata. |
| `GET /api/admin/work-definitions/{definitionId}` | Inspect one definition and its versions. |
| `POST /api/admin/executions/selection-diagnostics` | Diagnose worker selection for a planned create request without side effects. |
| `POST /api/admin/executions` | Create one execution and explicit worker assignment. |
| `POST /api/admin/executions/{executionId}/cancel` | Cancel a `QUEUED` or `ASSIGNED` execution before real Agent-side execution begins. |
| `GET /api/admin/executions` | Monitor execution history. |
| `GET /api/admin/executions/{executionId}` | Inspect one execution. |
| `GET /api/admin/workers/{workerId}` | Inspect the target worker and recent execution activity. |
| `GET /api/admin/executions/{executionId}/artifacts` | List output artifact metadata for one execution. |

The Work Definition API helps an admin client select a definition and version. The Admin Worker API helps inspect target workers, resource fields, and reported capabilities used by `AUTO` and `PREFER`. This API creates the execution. [Worker Selection](worker-selection.md) explains assignment mode behavior. The Admin Execution API monitors the result. Artifact endpoints list and download output after the Agent uploads it.

## Manual Smoke Flow

1. Log in as an admin and capture the JWT.
2. List work definitions and capture `latestVersionId`.
3. Pick an approved worker for `REQUIRE`, or use `AUTO`/`PREFER` worker selection.
4. Create the execution with `POST /api/admin/executions`.
5. Poll `GET /api/admin/executions/{executionId}`.
6. Read output artifact metadata and download artifacts with the existing artifact API.

Example:

```http
GET /api/admin/work-definitions?logicalId=localhive.docker.workload&limit=20&offset=0
Accept: application/json
Authorization: Bearer {{auth_token}}
```

```http
GET /api/admin/workers/{workerId}
Accept: application/json
Authorization: Bearer {{auth_token}}
```

```http
POST /api/admin/executions
Content-Type: application/json
Authorization: Bearer {{auth_token}}
```

```http
GET /api/admin/executions/{executionId}
Accept: application/json
Authorization: Bearer {{auth_token}}
```

```http
GET /api/admin/executions/{executionId}/artifacts
Accept: application/json
Authorization: Bearer {{auth_token}}
```

## Current Limitations

- Work Definition management remains read-only.
- Worker selection runs only during `POST /api/admin/executions`.
- No background scheduler.
- No periodic assignment process.
- No multi-worker or distributed execution.
- No Workload lifecycle support.
- Only `TASK` creation.
- Limited executor support.
- Docker Master allowlist is currently `alpine:3.20`.
- No GPU support.
- Cancellation is limited to `QUEUED` and `ASSIGNED` executions through the separate cancel endpoint.
- No retry or requeue endpoint.
- No YAML import.
- No frontend UI yet.

## Future Extensions

Future work may add:

- real scheduler loop,
- queueing without immediate assignment,
- richer worker capability diagnostics,
- Workload lifecycle creation,
- Agent-side running cancellation, retry, and requeue after explicit design,
- YAML or template-based create flow,
- safe parameter schema DTO,
- frontend create execution wizard,
- Docker policy alignment between Master and Agent,
- GPU support after explicit design,
- distributed or multi-worker execution later.
