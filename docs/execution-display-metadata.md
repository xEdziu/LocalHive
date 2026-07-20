# Execution Display Metadata

Execution display metadata gives Master, Agent, and future UI surfaces a stable human-readable name for an execution.

Before this metadata, an Agent dashboard or future execution table could only show technical values such as `localhive.docker.workload / SUCCEEDED / 506 ms`. With display metadata, the same execution can be shown as `Output Artifact Smoke Test / SUCCEEDED / 655 ms` while preserving the technical executor id for routing and compatibility.

## Core Concept

`displayNameSnapshot` is stored on `WorkExecution` when the execution is created. It is a snapshot, not a live pointer to a Work Definition or Work Instance name. Historical execution rows therefore keep the same display text even if definition names, instance names, or fallback naming rules change later.

The field is intended for UI, logs, dashboards, and history views. It is not used for execution routing, authorization, worker assignment, Docker invocation, paths, filenames, artifact identifiers, or lease validation.

## Naming Rules

Current execution naming follows these rules:

1. An explicit `displayName` is trimmed and stored.
2. A blank or null explicit `displayName` falls back to derived naming.
3. NO_OP smoke executions use `NO-OP smoke test`.
4. Docker smoke executions use `Docker workload: <image>` when the resolved configuration contains a nonblank image.
5. Docker smoke executions without an image use `Docker workload`.
6. Generic fallback naming uses a humanized executor id, or the logical identifier when the executor id is unavailable.
7. Display names are limited to 255 characters.
8. A too-long `displayName` submitted through the Docker dev smoke endpoint returns `400`.

## Dev Smoke Endpoints

The dev-only smoke endpoints create executions with display names for local Agent and Master verification.

`POST /api/dev/smoke/workers/{workerId}/no-op` creates a NO_OP execution named `NO-OP smoke test`.

`POST /api/dev/smoke/workers/{workerId}/docker-workload` accepts an optional `displayName` JSON field inside the existing Docker smoke request body. When a request body is provided, the other Docker smoke fields such as image, command, resources, and GPU requirement are still validated by the endpoint.

```json
{
  "image": "alpine:3.20",
  "command": ["sh", "-c", "echo LocalHive Docker workload"],
  "timeoutSeconds": 30,
  "resources": {
    "memoryMb": 128,
    "cpuCores": 1
  },
  "gpu": {
    "required": false
  },
  "displayName": "Output Artifact Smoke Test"
}
```

When `displayName` is omitted or blank, Docker smoke execution naming falls back to the Docker image, for example `Docker workload: alpine:3.20`.

This is dev-smoke support only. It is not a production workload naming UI, YAML import feature, or scheduler feature.

## Worker Claim Response

The worker claim endpoint includes the display name as an additive field:

```http
POST /api/workers/{workerId}/assigned-executions/claim-next
```

Example response shape:

```json
{
  "executionId": "00000000-0000-0000-0000-000000000000",
  "displayName": "Output Artifact Smoke Test",
  "executorId": "localhive.docker.workload",
  "executorContractVersion": 1,
  "configuration": {},
  "requiredRamMb": 128,
  "requiredCpuCores": 1,
  "gpuRequired": false,
  "leaseToken": "<returned-on-claim>",
  "leaseExpiresAt": "2026-07-19T12:00:00"
}
```

Existing claim and lease semantics are unchanged. Master still returns the raw lease token only in the claim response and stores only its hash.

## Admin Execution API

The read-only [Admin Execution API](admin-execution-api.md) exposes `displayName` in both execution list and detail responses. This lets future Master admin UI surfaces show the human-readable snapshot while keeping technical executor fields available for inspection.

## Database

Flyway V10 introduces `work_executions.display_name_snapshot` as `VARCHAR(255)`. The column is required and constrained to be nonblank after trimming. Existing rows are backfilled from the related Work Instance display name, Work Definition Version name, Work Definition logical identifier, or `Work execution` when no better source is available.

See [Database Schema](database.md) for the migration chain and table-level notes.

## Security

`displayNameSnapshot` is user-visible metadata. It is length-limited to 255 characters, but it is not a security principal and must not be used as:

- a path,
- a filename,
- a shell argument,
- a Docker argument,
- an authorization input,
- a storage key.

API keys and lease tokens are not included in display metadata. Future UI and logging hardening may normalize or escape control characters before rendering display names.

## Compatibility

M6.2a added `displayName` to the Master worker claim response. AU0 updated the Agent claim DTO to consume `displayName` and tolerate additive unknown JSON fields.

The current Agent after AU0 handles claim responses with or without `displayName`. Older Agent versions that do not tolerate unknown claim fields may fail against a Master that returns `displayName`, so Master M6.2a should be used at runtime with AU0 or newer Agent builds.

Runtime smoke testing has passed with Master M6.2a and Agent AU0.

## Current Limitations

Current display metadata is intentionally small:

- there is no full Master execution UI yet,
- display names mostly come from dev-smoke requests, Work Instance names, and fallback logic,
- there are no advanced naming templates,
- there is no internationalization layer,
- there is no control-character normalization yet,
- there is no user-facing workload naming screen.

## Future Extensions

Possible future extensions include:

- using `displayNameSnapshot` in Master execution tables,
- using display names in an Agent UI redesign,
- adding workload or instance naming flows,
- improving names for Minecraft and Fabric tasks after those workloads are implemented,
- adding research or benchmark run names after those domains are designed.
