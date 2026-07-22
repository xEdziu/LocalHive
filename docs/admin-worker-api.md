# Admin Worker API

M8 adds a read-only admin endpoint for inspecting one Worker in more detail.

The existing admin worker list gives a cluster overview. A future Master frontend also needs a single-worker page that can show identity, hardware, status, heartbeat information, and recent execution activity without calling worker protocol endpoints or exposing runtime secrets.

The Admin Worker API is read-only. It does not approve workers, pause or resume workers, regenerate API keys, update hardware or allocation fields, or process heartbeats.

## Worker Detail

```http
GET /api/admin/workers/{workerId}
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Behavior:

- an existing worker returns `200`,
- a missing worker returns `404`,
- an invalid `workerId` UUID returns `400`,
- the endpoint is read-only,
- the endpoint does not approve workers,
- the endpoint does not pause or resume workers,
- the endpoint does not regenerate API keys,
- the endpoint does not update hardware, shared RAM allocation, or heartbeat state.

## Response Shape

Example:

```json
{
  "workerId": "00000000-0000-0000-0000-000000000000",
  "hostname": "Adrian-PC",
  "ipAddress": "192.168.66.3",
  "osType": "Windows 11",
  "hardware": {
    "totalRamMb": 32768,
    "sharedRamMb": 4096,
    "cpuCores": 16,
    "gpuName": "RTX 5080"
  },
  "status": {
    "approval": "APPROVED",
    "connection": "ONLINE",
    "availability": "AVAILABLE"
  },
  "heartbeat": {
    "lastSeenAt": "2026-07-20T10:00:00",
    "lastHeartbeatAt": "2026-07-20T10:00:00",
    "pauseEnabled": false
  },
  "capabilities": {
    "reportedAt": "2026-07-20T10:00:00",
    "executors": [
      {
        "executorId": "localhive.no-op",
        "executorContractVersion": 1,
        "enabled": true
      },
      {
        "executorId": "localhive.docker.workload",
        "executorContractVersion": 1,
        "enabled": true
      }
    ],
    "docker": {
      "enabled": true,
      "allowedImages": [
        "alpine:3.20"
      ],
      "maxMemoryMb": 4096,
      "maxCpuCores": 8,
      "gpuAllowed": false
    }
  },
  "currentExecution": {
    "executionId": "11111111-1111-1111-1111-111111111111",
    "displayName": "M6.1 Storage Smoke",
    "status": "RUNNING",
    "executorId": "localhive.docker.workload",
    "executorContractVersion": 1,
    "createdAt": "2026-07-20T10:00:00",
    "startedAt": "2026-07-20T10:00:02",
    "completedAt": null,
    "durationMs": null,
    "outputArtifactCount": 0
  },
  "lastExecution": {
    "executionId": "11111111-1111-1111-1111-111111111111",
    "displayName": "M6.1 Storage Smoke",
    "status": "RUNNING",
    "executorId": "localhive.docker.workload",
    "executorContractVersion": 1,
    "createdAt": "2026-07-20T10:00:00",
    "startedAt": "2026-07-20T10:00:02",
    "completedAt": null,
    "durationMs": null,
    "outputArtifactCount": 0
  },
  "recentExecutions": [
    {
      "executionId": "11111111-1111-1111-1111-111111111111",
      "displayName": "M6.1 Storage Smoke",
      "status": "RUNNING",
      "executorId": "localhive.docker.workload",
      "executorContractVersion": 1,
      "createdAt": "2026-07-20T10:00:00",
      "startedAt": "2026-07-20T10:00:02",
      "completedAt": null,
      "durationMs": null,
      "outputArtifactCount": 0
    },
    {
      "executionId": "22222222-2222-2222-2222-222222222222",
      "displayName": "Output Artifact Smoke",
      "status": "SUCCEEDED",
      "executorId": "localhive.docker.workload",
      "executorContractVersion": 1,
      "createdAt": "2026-07-20T09:00:00",
      "startedAt": "2026-07-20T09:00:01",
      "completedAt": "2026-07-20T09:00:05",
      "durationMs": 4000,
      "outputArtifactCount": 2
    }
  ]
}
```

If the newest assigned execution is active, `currentExecution` and `lastExecution` can reference the same execution. If no active execution exists, `currentExecution` is `null`.

## Worker Identity

| Field | Description |
| --- | --- |
| `workerId` | Worker UUID. |
| `hostname` | Registered worker hostname. |
| `ipAddress` | Last registered worker IP address. |
| `osType` | Registered operating system description. |

## Hardware

| Field | Description |
| --- | --- |
| `totalRamMb` | Total RAM reported by the worker, in MiB. |
| `sharedRamMb` | RAM currently shared with LocalHive, in MiB. |
| `cpuCores` | CPU core count reported by the worker. |
| `gpuName` | Optional GPU name reported by the worker. |

## Status

Worker state is split into the current persisted dimensions:

| Field | Description |
| --- | --- |
| `approval` | Approval status, for example `PENDING` or `APPROVED`. |
| `connection` | Connection status, for example `ONLINE` or `OFFLINE`. |
| `availability` | Availability status, for example `AVAILABLE` or `PAUSED`. |

## Heartbeat

| Field | Description |
| --- | --- |
| `lastHeartbeatAt` | Timestamp of the last heartbeat known to Master. |
| `pauseEnabled` | Derived from `availability == PAUSED`. |
| `lastSeenAt` | Currently mirrors `lastHeartbeatAt` because Master does not maintain a separate last-seen source yet. |

## Capabilities

`capabilities` is the latest safe capability snapshot reported by the Agent heartbeat. It is `null` when the worker has never reported capabilities.

The section may contain:

- `reportedAt`,
- `executors`,
- Docker policy summary metadata.

Capabilities are documented in [Agent Capabilities](agent-capabilities.md). Starting in M13, the latest snapshot is also used by [Worker Selection](worker-selection.md) for `AUTO` and `PREFER` eligibility. `REQUIRE` remains manual and ignores capabilities.

## Current Execution

`currentExecution` is the newest execution assigned to this worker in an active status:

- `ASSIGNED`
- `CLAIMED`
- `RUNNING`

It is `null` when no active execution exists for the worker.

For running or partially recorded executions, `durationMs` is `null` when `completedAt` is missing. Master does not invent a fake duration.

## Last Execution

`lastExecution` is the newest execution assigned to this worker regardless of status.

It is `null` when the worker has no execution history.

## Recent Executions

`recentExecutions` is a newest-first preview of the worker execution history.

- maximum size: `5`
- preview only
- may include active and terminal executions

Use the [Admin Execution API](admin-execution-api.md) for the full worker execution history:

```http
GET /api/admin/executions?workerId={workerId}&limit=50&offset=0
```

Use the [Admin Create Execution API](admin-create-execution-api.md) when an admin client needs to create a one-off execution. [Worker Selection](worker-selection.md) uses the worker status, resource fields, and latest capability snapshot for `AUTO` and `PREFER` assignment modes.

## Artifact Count

`outputArtifactCount` is a database-based count of `EXECUTION_OUTPUT` artifacts linked to the execution.

The worker detail endpoint:

- does not scan the filesystem,
- does not load artifact file contents,
- does not expose artifact physical storage paths.

Use artifact endpoints for full output artifact metadata and downloads:

```http
GET /api/admin/executions/{executionId}/artifacts
GET /api/admin/artifacts/{artifactId}/download
```

## Security / Non-Exposed Fields

The worker detail response does not expose:

- worker API key,
- worker API key hash,
- password hash,
- lease token,
- lease token hash,
- `leaseExpiresAt`,
- raw lease fields,
- `requestedConfigurationSnapshot`,
- `resolvedConfigurationSnapshot`,
- `storagePath`,
- `dataRoot`,
- physical absolute storage path,
- Master URL,
- local Agent config path,
- full Agent config JSON,
- credential store details,
- task history,
- workspace or output paths,
- artifact file contents,
- stack traces.

## Relation To Other Admin APIs

| Endpoint | Purpose |
| --- | --- |
| `GET /api/admin/workers` | Worker overview list. |
| `GET /api/admin/workers/{workerId}` | One worker detail card or page. |
| `POST /api/admin/executions` | Create a one-off execution and assign it with `REQUIRE`, `AUTO`, or `PREFER`. |
| `GET /api/admin/executions?workerId={workerId}&limit=50&offset=0` | Full execution history for one worker. |
| `GET /api/admin/executions/{executionId}` | One execution detail page. |
| `GET /api/admin/executions/{executionId}/artifacts` | Output artifact metadata for one execution. |

The worker detail endpoint provides only a small execution preview. Execution list/detail and artifact endpoints remain the source for deeper execution and output file inspection.

See [Worker Selection](worker-selection.md) for how `APPROVED`, `ONLINE`, `AVAILABLE`, `sharedRamMb`, `cpuCores`, active execution state, and M12 [Agent Capabilities](agent-capabilities.md) affect `AUTO` and `PREFER` assignment.

## Current Limitations

- read-only only,
- no worker edit UI or API,
- no worker pause or resume admin endpoint,
- no API key regeneration endpoint,
- no live streaming status,
- no separate `lastSeenAt` source yet,
- `recentExecutions` is only a preview with maximum size `5`,
- no date range filtering in worker detail,
- no inline artifact preview,
- no frontend UI yet.

## Future Extensions

Future work may add:

- Master frontend worker detail page,
- worker actions after explicit design,
- API key rotation flow,
- pause and resume controls,
- richer heartbeat telemetry,
- separate `lastSeenAt` source,
- current execution progress,
- worker execution history table using the Admin Execution API,
- artifact browser integration.
