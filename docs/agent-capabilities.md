# Agent Capabilities

M12 adds Agent capability reporting as safe operational metadata. M13 uses the latest stored snapshot as part of `AUTO` and `PREFER` worker selection eligibility.

## Purpose

Agents may include a `capabilities` object in worker heartbeat requests. Master treats this as the latest known snapshot from the Agent, stores one current snapshot per worker, exposes the safe metadata through Admin Worker Detail, and uses it for M13 capability-aware worker selection.

Capabilities do not change worker heartbeat authentication, worker claim, lease, report, Docker runtime, workspace, output artifact, or storage behavior. They are an eligibility input for `AUTO` and `PREFER`; `REQUIRE` remains manual and ignores capabilities.

Use [Selection Diagnostics API](selection-diagnostics-api.md) to debug capability mismatches that make workers ineligible for `AUTO` or `PREFER`.

## Heartbeat Payload

The heartbeat request keeps the existing required fields and adds an optional `capabilities` field:

```json
{
  "pauseEnabled": false,
  "sharedRamMb": 4096,
  "capabilities": {
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
      "allowedImages": ["alpine:3.20"],
      "maxMemoryMb": 4096,
      "maxCpuCores": 8,
      "gpuAllowed": false
    }
  }
}
```

Compatibility behavior:

- `capabilities` is optional.
- Older Agents that send only `pauseEnabled` and `sharedRamMb` still work.
- A heartbeat without `capabilities` does not clear the previous capability snapshot.
- A valid later heartbeat with `capabilities` replaces the previous snapshot for that worker.
- Invalid capabilities are rejected before the snapshot is saved, so a bad report must not partially update a previous valid snapshot.

Worker registration, claim, lease, and report endpoints are unchanged by M12/M13 capability reporting and selection.

## Persistence

Capability snapshots are stored in:

```text
worker_capabilities
```

The table is created by:

```text
localhive-backend/src/main/resources/db/migration/V11__add_worker_capabilities.sql
```

Persistence model:

- one latest snapshot per worker,
- `worker_id` is the primary key,
- `worker_id` references `workers(id)` with cascade delete,
- `reported_at` is required,
- `executors` is stored as PostgreSQL `JSONB`,
- Docker allowed images are stored as PostgreSQL `JSONB`,
- Docker enabled, resource limits, and GPU policy summary are stored as scalar columns,
- no capability history table exists in M12,
- V1-V10 migrations are unchanged.

The stored snapshot is intended for admin visibility and M13 automatic worker selection. It does not contain physical paths, raw Agent config, credential backend details, task history, or secrets.

## Admin Worker Detail

Admin Worker Detail exposes the current snapshot:

```http
GET /api/admin/workers/{workerId}
```

Example response fragment:

```json
{
  "capabilities": {
    "reportedAt": "2026-07-20T19:45:04.54822",
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
      "allowedImages": ["alpine:3.20"],
      "maxMemoryMb": 4096,
      "maxCpuCores": 8,
      "gpuAllowed": false
    }
  }
}
```

If a worker has never reported capabilities, Admin Worker Detail returns:

```json
{
  "capabilities": null
}
```

The endpoint remains ADMIN-only. Worker API keys cannot read Admin Worker Detail.

## Validation

Master validates reported capabilities before saving them.

Current validation rules include:

- executor list is bounded,
- `executorId` is required and nonblank,
- executor contract version must be positive,
- Docker allowed image list is bounded,
- Docker allowed image entries must be nonblank,
- Docker memory and CPU values must be non-negative when present,
- invalid structured payloads are rejected instead of being persisted.

## Security / Non-Exposed Fields

Capability snapshots and Admin Worker Detail capability metadata do not expose:

- API keys,
- API key hashes,
- password hashes,
- Master URL,
- local Agent config path,
- full Agent config JSON,
- credential store details,
- task history,
- workspace or output paths,
- lease tokens,
- lease token hashes,
- `storagePath`,
- `dataRoot`,
- physical paths,
- stack traces,
- internal exception details.

## Relation To Worker Selection

M13 worker selection still uses existing worker state and resource fields:

- approval status,
- connection status,
- availability status,
- active execution state,
- shared RAM,
- CPU cores,
- GPU-required rejection.

For `AUTO` and `PREFER`, M13 adds capability fit:

- latest stored capability snapshot must exist,
- matching `executorId` is required,
- matching `executorContractVersion` is required,
- the matching executor must have `enabled == true`,
- Docker workloads additionally require Docker summary, `docker.enabled == true`, an allowed image match, and optional Docker memory/CPU policy fit.

Capability fit is an eligibility filter. It is not a scoring factor, and no capability TTL or staleness rule is added in M13.

`REQUIRE` does not use capabilities. An admin can still assign an execution manually to an `APPROVED` worker without a capability snapshot.

The Agent remains the final enforcement point for local Docker policy.

## Current Limitations

- no capability history,
- no scheduler or background assignment loop,
- no Docker policy synchronization from Agent to Master enforcement,
- no GPU execution support,
- no multi-worker execution,
- no YAML import,
- no Minecraft workload support,
- no WebSocket, SOAP, or research telemetry transport.

## Future Extensions

Future work may add:

- richer executor metadata,
- capability versioning,
- cached Docker health reporting,
- GPU capability reporting after an explicit GPU design,
- Master frontend display for capability snapshots.
