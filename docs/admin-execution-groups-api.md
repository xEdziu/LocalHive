# Admin Execution Groups API

M17 added the read-only admin foundation for sharded workloads. M18 added the first create and event-driven scheduling foundation for Docker shard groups. M19 adds `mergeMode = AGENT`, where Master creates a normal Docker `MERGE` execution after the shard phase is ready. M20 adds admin group cancel and manual one-shot reconcile. M21 adds a derived observability summary to the group detail response and deterministic child execution ordering. M22 adds read-only group output artifact discovery and a lightweight group artifact summary. M23 adds explicit lifecycle action metadata to the group detail response. M24 adds a derived activity feed endpoint for admin polling. M25 adds a lightweight Server-Sent Events stream for admin UI live updates. M27 adds a separate JSON-over-WebSocket research adapter for selected group read/control operations. M28 adds a separate SOAP/XML research adapter for selected group read/control operations.

An `ExecutionGroup` is group-level metadata for sharding. Child work remains ordinary `WorkExecution` records with nullable group metadata. Master creates `SHARD` children, expands a controlled Docker command template for each shard, assigns as many shards as currently eligible workers allow, and schedules later waves when child executions report terminal status. With `mergeMode = AGENT`, Master later creates one `MERGE` child execution after shard policy allows merge.

The Agent does not know about sharding. Every shard is still claimed, leased, executed, and reported as a normal `WorkExecution`.

## Security

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Responses are safe admin summaries. They do not expose raw configuration snapshots, executor configuration, raw merge plans, API keys, password hashes, lease token, lease token hash, `leaseExpiresAt`, storage paths, `dataRoot`, physical paths, artifact contents, or stack traces.

## Create Execution Group

```http
POST /api/admin/execution-groups
```

Behavior:

- creates one `ExecutionGroup` and `shardCount` child `WorkExecution` rows transactionally,
- supports approved `TASK` definition versions for `localhive.docker.workload`,
- creates only `groupRole = SHARD` children during the initial request,
- for `mergeMode = AGENT`, stores an internal merge plan and creates the `MERGE` child only after shard outputs are ready,
- runs an initial scheduling pass after child creation,
- returns `201 Created` with the group detail response,
- invalid request data returns `400`,
- missing work definition version returns `404`.

Request shape:

```json
{
  "displayName": "M18 Sharded Optimization",
  "workDefinitionVersionId": "00000000-0000-0000-0000-000000000000",
  "shardCount": 4,
  "mergeMode": "NONE",
  "failurePolicy": "FAIL_FAST",
  "assignmentMode": "AUTO",
  "workerId": null,
  "configurationTemplate": {
    "image": "alpine:3.20",
    "commandTemplate": [
      "sh",
      "/workspace/optimize.sh",
      "{{shardIndex}}",
      "{{shardCount}}"
    ],
    "timeoutSeconds": 30,
    "resources": {
      "memoryMb": 128,
      "cpuCores": 1
    },
    "gpu": {
      "required": false
    },
    "workspace": {
      "artifactId": "00000000-0000-0000-0000-000000000000",
      "mountPath": "/workspace",
      "readOnly": true
    }
  }
}
```

Request fields:

| Field | Required | Description |
| --- | --- | --- |
| `displayName` | yes | Group display name. Blank or absent values return `400`. |
| `workDefinitionVersionId` | yes | Approved Docker work definition version id. |
| `shardCount` | yes | Number of child shard executions to create. Must be greater than `0`. |
| `mergeMode` | no | Defaults to `NONE`. `NONE` and `AGENT` are supported. `MASTER` returns `400`. |
| `failurePolicy` | no | Defaults to `FAIL_FAST`. `ALLOW_PARTIAL` is also supported for final group status derivation. |
| `assignmentMode` | no | Defaults to `AUTO`. `PREFER` is supported for the first shard. `REQUIRE` returns `400` in M18. |
| `workerId` | conditional | Must be absent for `AUTO`. Required for `PREFER`. |
| `configurationTemplate` | yes | Docker configuration template. It is validated with the existing Docker workload rules after command template expansion. |
| `mergeConfigurationTemplate` | conditional | Required for `mergeMode = AGENT`; must be absent for `mergeMode = NONE`. It is an internal Docker merge template and is not returned by admin read APIs. |

`configurationTemplate` is the base Docker configuration used for every shard. It must include `commandTemplate` instead of `command`. The final child execution configuration stores only the expanded `command`; `commandTemplate` is not stored in child execution snapshots.

Command template rules:

- `commandTemplate` must be a non-empty JSON array,
- every element must be a nonblank string,
- supported placeholders are `{{shardIndex}}` and `{{shardCount}}`,
- unsupported or malformed placeholders return `400`,
- substitution is done by Master on individual command list elements,
- LocalHive does not concatenate a shell command string or add shell interpolation.

Example expansion for `shardCount = 4`:

```json
["sh", "/workspace/optimize.sh", "0", "4"]
["sh", "/workspace/optimize.sh", "1", "4"]
["sh", "/workspace/optimize.sh", "2", "4"]
["sh", "/workspace/optimize.sh", "3", "4"]
```

## AGENT Merge

`mergeMode = AGENT` is implemented as a normal Docker `WorkExecution` with `groupRole = MERGE`. The Agent remains unaware of sharding and merge semantics; it claims, leases, runs, uploads output artifacts, and reports terminal status exactly as it does for any other Docker execution.

Create requests using `mergeMode = AGENT` must include `mergeConfigurationTemplate`:

```json
{
  "mergeMode": "AGENT",
  "mergeConfigurationTemplate": {
    "image": "alpine:3.20",
    "commandTemplate": [
      "sh",
      "/workspace/merge.sh",
      "{{inputManifestPath}}"
    ],
    "timeoutSeconds": 30,
    "resources": {
      "memoryMb": 128,
      "cpuCores": 1
    },
    "gpu": {
      "required": false
    },
    "workspace": {
      "artifactId": "00000000-0000-0000-0000-000000000000",
      "mountPath": "/workspace",
      "readOnly": true
    }
  }
}
```

Merge command template rules:

- `commandTemplate` must be a non-empty JSON array,
- every element must be a nonblank string,
- supported placeholders are `{{executionGroupId}}`, `{{shardCount}}`, and `{{inputManifestPath}}`,
- unsupported or malformed placeholders return `400`,
- substitution is done by Master on individual command list elements,
- LocalHive does not concatenate a shell command string or add shell interpolation.

The `workspace.artifactId` in `mergeConfigurationTemplate` points to the base merge workspace uploaded to Master, for example a ZIP containing `merge.sh`. When the merge becomes ready, Master creates a derived `WORKSPACE_PACKAGE` artifact for the merge execution. The derived workspace contains base workspace files plus successful shard outputs:

```text
workspace/
|-- merge.sh
`-- inputs/
    |-- manifest.json
    `-- shards/
        |-- 0/
        |   `-- result.json
        `-- 1/
            `-- summary.txt
```

The manifest is available at `/workspace/inputs/manifest.json` and contains safe metadata only: execution group id, shard count, failure policy, successful shard ids/indexes/statuses, artifact ids, original relative paths, and container input paths. It does not contain API keys, lease tokens, hashes, raw configuration snapshots, storage paths, `dataRoot`, or physical filesystem paths.

The derived workspace uses the existing workspace package artifact flow and is downloaded by the Agent through the existing worker API key plus execution lease mechanism. The Docker mount remains read-only at `/workspace`; merge output is still written by the Agent to `/output` and uploaded through the existing output artifact endpoint before terminal report.

Response shape:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "displayName": "M18 Sharded Optimization",
  "status": "RUNNING",
  "mergeMode": "NONE",
  "failurePolicy": "FAIL_FAST",
  "shardCount": 4,
  "totalExecutions": 4,
  "activeExecutions": 2,
  "terminalExecutions": 0,
  "childExecutionCounts": {
    "QUEUED": 2,
    "ASSIGNED": 2
  },
  "createdAt": "2026-07-21T10:00:00",
  "updatedAt": "2026-07-21T10:00:01",
  "completedAt": null,
  "cancelledAt": null,
  "failureCode": null,
  "failureMessage": null
}
```

## Scheduling

Scheduling is event-driven:

1. Group creation creates all child shards as queued executions.
2. Initial scheduling assigns as many queued shards as eligible workers allow.
3. When a child shard reaches a terminal status through the existing worker report flow, Master tries to assign the next queued shard.
4. For `mergeMode = AGENT`, when shard policy allows merge, Master prepares the derived workspace, creates one queued `MERGE` execution, and tries to assign it with `AUTO` selection.
5. Admins can trigger the same safe scheduling/reconciliation pass manually with `POST /api/admin/execution-groups/{executionGroupId}/reconcile`.

Shard scheduling reuses M13 capability-aware worker selection. Eligibility still requires an approved, online, available worker with no active execution, enough shared RAM and CPU, a matching enabled executor capability, and a Docker policy that allows the requested image and resources.

More shards than workers are supported. If no worker is eligible during creation, after a child terminal report, or during manual reconcile, queued shards remain `QUEUED` and the group remains safe to inspect through the read APIs. If no worker is eligible for the merge execution, the `MERGE` child remains `QUEUED` and the group remains `MERGING`. M20 does not include a background scheduler, so a future worker becoming eligible does not automatically trigger group scheduling until another event or manual reconcile triggers scheduling.

## Group Status Derivation

For `mergeMode = NONE`:

- if any child is queued or non-terminal, the group is `RUNNING`,
- if all children are `SUCCEEDED`, the group is `SUCCEEDED`,
- if all children are terminal and at least one child failed, cancelled, or expired, `FAIL_FAST` derives `FAILED`,
- if all children are terminal, at least one child succeeded, and `failurePolicy = ALLOW_PARTIAL`, the group derives `PARTIALLY_FAILED`.

M18 does not aggressively interrupt or kill running children for `FAIL_FAST`. It also preserves future cancellation and expiry states if such a state is already present.

For `mergeMode = AGENT`:

- before shard policy allows merge, the group is `RUNNING`,
- with `FAIL_FAST`, any failed, cancelled, or expired shard fails the group and no `MERGE` execution is created,
- with `ALLOW_PARTIAL`, the merge is created if at least one shard succeeded,
- if no shard succeeded, the group becomes `FAILED` and no `MERGE` execution is created,
- when merge is queued, assigned, claimed, or running, the group is `MERGING`,
- all shards succeeded and merge succeeded derives `SUCCEEDED`,
- mixed shard results with `ALLOW_PARTIAL` and merge succeeded derives `PARTIALLY_FAILED`,
- merge failure, cancellation, or expiry derives `FAILED`.

If group cancellation was requested and the group is `CANCELLING`, normal success, partial failure, and merge derivation are suppressed. Once no child execution remains `CLAIMED` or `RUNNING`, the group becomes `CANCELLED`.

## Cancel Execution Group

```http
POST /api/admin/execution-groups/{executionGroupId}/cancel
```

Request body is optional:

```json
{
  "reason": "Admin requested cancellation"
}
```

Behavior:

- existing non-terminal groups return `200` with the safe group detail response,
- missing group returns `404`,
- terminal groups return `409`,
- blank or absent `reason` uses `Execution group cancelled by admin.`,
- nonblank `reason` is trimmed,
- `reason` longer than 500 characters returns `400`,
- group failure code is `ADMIN_GROUP_CANCELLED`,
- queued and assigned child executions are marked `CANCELLED`,
- claimed and running child executions are not interrupted,
- terminal child executions are unchanged,
- assignments and artifacts are not deleted,
- no Docker container is killed,
- no Agent interrupt is sent,
- worker claim/report protocol is unchanged.

If no child execution remains `CLAIMED` or `RUNNING`, the group becomes `CANCELLED` and both `cancelledAt` and `completedAt` are set. If any child execution remains `CLAIMED` or `RUNNING`, the group becomes `CANCELLING`; `cancelledAt`, `failureCode`, and `failureMessage` are set, but `completedAt` remains null until active children report terminal status.

Cancelable group statuses:

```text
CREATED
SCHEDULING
RUNNING
MERGING
CANCELLING
```

Terminal statuses rejected by cancel:

```text
SUCCEEDED
FAILED
PARTIALLY_FAILED
CANCELLED
EXPIRED
```

## Reconcile Execution Group

```http
POST /api/admin/execution-groups/{executionGroupId}/reconcile
```

Behavior:

- existing groups return `200` with the safe group detail response,
- missing group returns `404`,
- terminal groups are treated as safe no-ops,
- `CANCELLING` groups never schedule work and are finalized to `CANCELLED` when no child remains `CLAIMED` or `RUNNING`,
- active groups schedule eligible queued `SHARD` executions,
- `mergeMode = AGENT` groups create one `MERGE` execution when shard policy allows merge and no merge already exists,
- queued `MERGE` executions are scheduled when a worker is eligible,
- if no eligible worker exists, queued work remains `QUEUED`,
- repeated reconcile calls do not duplicate children, assignments, or merge executions,
- no Docker container is killed,
- no Agent interrupt is sent,
- no background scheduler is introduced.

## List Execution Groups

```http
GET /api/admin/execution-groups
```

Query parameters:

| Parameter | Required | Description |
| --- | --- | --- |
| `limit` | no | Page size. Defaults to `50`; maximum is `200`. |
| `offset` | no | Zero-based offset. Defaults to `0`. |
| `status` | no | Exact `ExecutionGroupStatus` enum value. |

Behavior:

- results are newest-first,
- pagination is offset-based,
- `offset` applies after filtering and sorting,
- `totalCount` respects the active `status` filter,
- empty pages return `items: []`,
- invalid `limit`, `offset`, or `status` returns `400`.

Response shape:

```json
{
  "items": [
    {
      "executionGroupId": "00000000-0000-0000-0000-000000000000",
      "displayName": "Optimization group",
      "status": "RUNNING",
      "mergeMode": "NONE",
      "failurePolicy": "FAIL_FAST",
      "shardCount": 4,
      "totalExecutions": 4,
      "createdAt": "2026-07-21T10:00:00",
      "updatedAt": "2026-07-21T10:00:00",
      "completedAt": null,
      "cancelledAt": null
    }
  ],
  "limit": 50,
  "offset": 0,
  "totalCount": 1
}
```

## Execution Group Detail

```http
GET /api/admin/execution-groups/{executionGroupId}
```

Behavior:

- existing group returns `200`,
- missing group returns `404`,
- invalid UUID uses current framework validation/error behavior.

Response shape:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "displayName": "Optimization group",
  "status": "RUNNING",
  "mergeMode": "NONE",
  "failurePolicy": "FAIL_FAST",
  "shardCount": 4,
  "totalExecutions": 4,
  "activeExecutions": 2,
  "terminalExecutions": 1,
  "childExecutionCounts": {
    "ASSIGNED": 1,
    "RUNNING": 1,
    "SUCCEEDED": 1,
    "QUEUED": 1
  },
  "createdAt": "2026-07-21T10:00:00",
  "updatedAt": "2026-07-21T10:00:00",
  "completedAt": null,
  "cancelledAt": null,
  "failureCode": null,
  "failureMessage": null,
  "observability": {
    "terminal": false,
    "cancelInProgress": false,
    "hasActiveChildren": true,
    "hasQueuedChildren": true,
    "canCancel": true,
    "canReconcile": true,
    "shards": {
      "total": 4,
      "queued": 1,
      "assigned": 1,
      "claimed": 0,
      "running": 1,
      "succeeded": 1,
      "failed": 0,
      "cancelled": 0,
      "expired": 0,
      "terminal": 1,
      "nonTerminal": 3
    },
    "merge": {
      "exists": false,
      "executionId": null,
      "status": null,
      "workerId": null,
      "workerHostname": null,
      "total": 0,
      "queued": 0,
      "assigned": 0,
      "claimed": 0,
      "running": 0,
      "succeeded": 0,
      "failed": 0,
      "cancelled": 0,
      "expired": 0,
      "terminal": 0,
      "nonTerminal": 0
    }
  },
  "lifecycleActions": {
    "cancel": {
      "available": true,
      "reasonCode": null,
      "reasonMessage": "Group can be cancelled.",
      "method": "POST",
      "path": "/api/admin/execution-groups/{executionGroupId}/cancel",
      "requiresBody": false,
      "reasonSupported": true
    },
    "reconcile": {
      "available": true,
      "reasonCode": null,
      "reasonMessage": "Group can be reconciled.",
      "method": "POST",
      "path": "/api/admin/execution-groups/{executionGroupId}/reconcile",
      "requiresBody": false,
      "reasonSupported": false
    }
  },
  "artifactSummary": {
    "totalArtifacts": 0,
    "shardArtifacts": 0,
    "mergeArtifacts": 0,
    "shardsWithArtifacts": 0,
    "mergeHasArtifacts": false,
    "preferredOutputSource": "NONE"
  }
}
```

`activeExecutions` counts `ASSIGNED`, `CLAIMED`, and `RUNNING` children. `terminalExecutions` counts `SUCCEEDED`, `FAILED`, `CANCELLED`, and `EXPIRED` children. `QUEUED` children are counted in `totalExecutions` and `childExecutionCounts`, but they are not active or terminal.

`observability` is a derived read model computed from the current `ExecutionGroup`, child `WorkExecution` rows, and safe assignment metadata. It is not persisted separately and is not an event log.

Observability fields:

| Field | Description |
| --- | --- |
| `terminal` | `true` for `SUCCEEDED`, `FAILED`, `PARTIALLY_FAILED`, `CANCELLED`, and `EXPIRED` group statuses. |
| `cancelInProgress` | `true` only when the group status is `CANCELLING`. |
| `hasActiveChildren` | `true` when at least one child is `CLAIMED` or `RUNNING`. This is narrower than legacy `activeExecutions`, which also counts `ASSIGNED`. |
| `hasQueuedChildren` | `true` when at least one child execution is `QUEUED`. |
| `canCancel` | Mirrors M20 cancel endpoint eligibility: `true` for `CREATED`, `SCHEDULING`, `RUNNING`, `MERGING`, and `CANCELLING`; `false` for terminal statuses. |
| `canReconcile` | `true` for the same non-terminal statuses where manual reconcile can safely recalculate, schedule, or finalize; `false` for terminal statuses. |
| `shards` | Counts only child executions with `groupRole = SHARD`. |
| `merge` | Counts only child executions with `groupRole = MERGE` and exposes safe metadata for a deterministic representative merge child. |

`shards` and `merge` expose per-status counts for `QUEUED`, `ASSIGNED`, `CLAIMED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, and `EXPIRED`, plus `total`, `terminal`, and `nonTerminal`. `merge.exists` is `false` and `merge.executionId`, `merge.status`, `merge.workerId`, and `merge.workerHostname` are `null` when no `MERGE` child exists. This is expected for `mergeMode = NONE`, for cancelled groups that never reached merge creation, and for `AGENT` merge groups whose shard policy has not allowed merge yet.

If more than one `MERGE` child exists because of a historical data issue, the response remains safe: merge counts cover all `MERGE` children, while `executionId`, `status`, `workerId`, and `workerHostname` use the earliest merge child by `createdAt`, then execution id.

`lifecycleActions` is a derived admin read model for clients that need stable action availability and user-facing unavailable reasons. It does not change cancel or reconcile behavior:

| Field | Description |
| --- | --- |
| `cancel.available` | `true` for `CREATED`, `SCHEDULING`, `RUNNING`, `MERGING`, and `CANCELLING`; `false` for terminal group statuses. |
| `cancel.reasonCode` | `null` when available; otherwise one of `GROUP_TERMINAL`, `GROUP_ALREADY_CANCELLED`, or `GROUP_EXPIRED`. |
| `cancel.reasonMessage` | Safe explanatory text for the current status. |
| `cancel.method` | Always `POST`. |
| `cancel.path` | `/api/admin/execution-groups/{executionGroupId}/cancel`. |
| `cancel.requiresBody` | `false`; an absent body and blank `reason` both use the default admin cancellation reason. |
| `cancel.reasonSupported` | `true`; a nonblank `reason` is accepted and trimmed, up to 500 characters. |
| `reconcile.available` | Mirrors `observability.canReconcile` and uses the same actionable statuses as cancel. |
| `reconcile.reasonCode` | `null` when available; otherwise one of `GROUP_TERMINAL`, `GROUP_ALREADY_CANCELLED`, or `GROUP_EXPIRED`. |
| `reconcile.reasonMessage` | Safe explanatory text for the current status. |
| `reconcile.method` | Always `POST`. |
| `reconcile.path` | `/api/admin/execution-groups/{executionGroupId}/reconcile`. |
| `reconcile.requiresBody` | `false`; reconcile does not use a request body. |
| `reconcile.reasonSupported` | `false`; reconcile has no admin reason field. |

`lifecycleActions.cancel.available` is consistent with `observability.canCancel`. `lifecycleActions.reconcile.available` is consistent with `observability.canReconcile`.

`artifactSummary` is a lightweight derived summary. It does not list artifact metadata in the group detail response:

| Field | Description |
| --- | --- |
| `totalArtifacts` | Number of `EXECUTION_OUTPUT` artifacts attached to child executions in the group. |
| `shardArtifacts` | Number of artifacts attached to `SHARD` children. |
| `mergeArtifacts` | Number of artifacts attached to `MERGE` children. |
| `shardsWithArtifacts` | Number of unique shard indexes that have at least one artifact. |
| `mergeHasArtifacts` | `true` when at least one `MERGE` child has at least one artifact. |
| `preferredOutputSource` | `MERGE` when merge artifacts exist, `SHARDS` when only shard artifacts exist, otherwise `NONE`. |

Use `GET /api/admin/execution-groups/{executionGroupId}/activity` when the client needs a chronological timeline. Use `GET /api/admin/execution-groups/{executionGroupId}/artifacts` when the client needs artifact ids and per-child artifact metadata.

## Execution Group Activity

```http
GET /api/admin/execution-groups/{executionGroupId}/activity
```

Behavior:

- existing group returns a derived best-effort timeline,
- missing group returns `404`,
- group with no children still returns `GROUP_CREATED` and any derivable current group status event,
- no events are persisted or created by this endpoint,
- no scheduler, cancel, reconcile, artifact upload, or artifact download behavior is changed,
- `mergeMode = NONE` does not fabricate `MERGE` events,
- `mergeMode = AGENT` shows `MERGE` events only when a `MERGE` child execution exists,
- artifact events are derived from `EXECUTION_OUTPUT` artifact links only.

Response shape:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "displayName": "S8 Artifact Discovery - AGENT Merge",
  "status": "SUCCEEDED",
  "mergeMode": "AGENT",
  "failurePolicy": "FAIL_FAST",
  "generatedAt": "2026-08-03T15:30:00",
  "events": [
    {
      "type": "GROUP_CREATED",
      "occurredAt": "2026-08-03T15:00:00",
      "message": "Execution group was created.",
      "executionId": null,
      "groupRole": null,
      "shardIndex": null,
      "workerId": null,
      "workerHostname": null,
      "artifactId": null,
      "relativePath": null,
      "status": "CREATED"
    },
    {
      "type": "SHARD_SUCCEEDED",
      "occurredAt": "2026-08-03T15:00:12",
      "message": "Shard 0 succeeded.",
      "executionId": "00000000-0000-0000-0000-000000000000",
      "groupRole": "SHARD",
      "shardIndex": 0,
      "workerId": "00000000-0000-0000-0000-000000000000",
      "workerHostname": "agent-01",
      "artifactId": null,
      "relativePath": null,
      "status": "SUCCEEDED"
    },
    {
      "type": "ARTIFACT_UPLOADED",
      "occurredAt": "2026-08-03T15:00:13",
      "message": "Artifact result.json was uploaded by shard 0.",
      "executionId": "00000000-0000-0000-0000-000000000000",
      "groupRole": "SHARD",
      "shardIndex": 0,
      "workerId": "00000000-0000-0000-0000-000000000000",
      "workerHostname": "agent-01",
      "artifactId": "00000000-0000-0000-0000-000000000000",
      "relativePath": "result.json",
      "status": null
    }
  ]
}
```

Event types:

| Type family | Meaning |
| --- | --- |
| `GROUP_CREATED` | Derived from `execution_groups.created_at`. |
| `GROUP_SCHEDULING`, `GROUP_RUNNING`, `GROUP_MERGING` | Current non-terminal group status when derivable. |
| `GROUP_SUCCEEDED`, `GROUP_PARTIALLY_FAILED`, `GROUP_FAILED`, `GROUP_CANCELLED`, `GROUP_EXPIRED` | Terminal group status derived from `completedAt`, `cancelledAt`, `updatedAt`, or `createdAt` fallback. |
| `GROUP_CANCELLING` | Cancellation was requested and a separate cancellation timestamp exists before final cancellation. |
| `SHARD_CREATED`, `MERGE_CREATED` | Child execution row exists. |
| `SHARD_ASSIGNED`, `MERGE_ASSIGNED` | Assignment exists or the child has an assignment timestamp. |
| `SHARD_CLAIMED`, `MERGE_CLAIMED` | Child has `claimedAt`. |
| `SHARD_RUNNING`, `MERGE_RUNNING` | Child has `startedAt`. |
| `SHARD_SUCCEEDED`, `SHARD_FAILED`, `SHARD_CANCELLED`, `SHARD_EXPIRED` | Terminal shard status with a known terminal timestamp. |
| `MERGE_SUCCEEDED`, `MERGE_FAILED`, `MERGE_CANCELLED`, `MERGE_EXPIRED` | Terminal merge status with a known terminal timestamp. |
| `ARTIFACT_UPLOADED` | `EXECUTION_OUTPUT` artifact is linked to a child execution. |

Sorting is deterministic:

1. `occurredAt` ascending.
2. Same-timestamp priority: group created, child created, assigned, claimed, running, shard terminal, merge terminal, artifact uploaded, group current or terminal.
3. `SHARD` events sort before `MERGE` events.
4. `shardIndex` ascending with nulls last.
5. `executionId` ascending.
6. artifact `relativePath` ascending.
7. `artifactId` ascending.

Safety:

- response fields are limited to group metadata and event-safe identifiers, timestamps, labels, statuses, worker id/hostname, artifact id, and artifact relative path,
- raw execution configuration snapshots are not exposed,
- raw merge plans are not exposed,
- API keys, password hashes, lease tokens, lease hashes, and `leaseExpiresAt` are not exposed,
- local filesystem paths, physical artifact storage paths, storage roots, artifact contents, stack traces, and internal exception details are not exposed.

This endpoint is a derived read model, not a persisted audit log. It reconstructs a best-effort timeline from the current `ExecutionGroup`, child `WorkExecution`, `ExecutionAssignment`, `Artifact`, and `ExecutionArtifact` rows. If an older transition has no separate timestamp column, M24 does not invent a synthetic event for that transition.

## Execution Group Activity Stream

```http
GET /api/admin/execution-groups/{executionGroupId}/activity/stream
```

M25 uses Server-Sent Events for UI-oriented live updates. The stream is one-way from Master to admin clients, works over ordinary HTTP, and is easier to inspect with curl or an HTTP client than a bidirectional protocol. M27 adds a separate WebSocket research adapter; it does not replace this admin UI stream.

M26 documents REST, WebSocket, and SOAP as research protocol adapters in [Research Protocol Contract](research-protocol-contract.md). The SSE stream described here remains an admin UI live update endpoint, not the WebSocket or SOAP research adapter.

Behavior:

- existing group opens a `text/event-stream` response,
- missing group returns `404` before opening a stream,
- ADMIN JWT is required,
- worker API keys are rejected,
- opening the stream does not create executions, assignments, artifacts, cancel requests, reconcile runs, scheduler work, or worker protocol changes,
- each polling iteration reads fresh safe derived models through the existing group detail and activity query paths,
- no transaction is held open for the lifetime of the stream,
- client disconnects terminate the polling loop and release stream resources.

Query parameters:

| Parameter | Required | Default | Validation | Description |
| --- | --- | --- | --- | --- |
| `pollIntervalMs` | no | `2000` | `500..10000` | How often Master refreshes group detail and activity snapshots. |
| `heartbeatIntervalMs` | no | `10000` | `1000..60000`, and must be `>= pollIntervalMs` | How often Master emits a heartbeat when no snapshot changed. Values below `pollIntervalMs` are rejected with `400`. |
| `closeOnTerminal` | no | `false` | `true` or `false` | When `true`, a terminal group causes a final `stream-complete` event and the stream is closed. |
| `maxEvents` | no | unlimited | `1..1000` | Diagnostic/test cap for SSE events emitted by this endpoint. The count includes every event actually sent, including `heartbeat` and `stream-complete` when present. |

SSE event names:

| Event | Data |
| --- | --- |
| `group-detail` | Existing safe `AdminExecutionGroupDetailResponseDto`. |
| `activity-snapshot` | Existing safe `AdminExecutionGroupActivityResponseDto`. |
| `heartbeat` | `executionGroupId`, `generatedAt`, and current group `status`. |
| `stream-complete` | `executionGroupId`, `generatedAt`, and `reason`, such as `MAX_EVENTS_REACHED` or `TERMINAL_GROUP_REACHED`. |
| `stream-error` | Safe error metadata only. Stack traces and internal storage details are not exposed. |

Initial stream sequence:

1. `group-detail`
2. `activity-snapshot`

After the initial sequence, Master polls the existing read models. It computes deterministic digests from serialized safe DTOs and excludes volatile `generatedAt` values from the digest. If the group detail digest changes, Master emits `group-detail`. If the activity digest changes, Master emits `activity-snapshot`. If neither digest changes and the heartbeat interval has elapsed, Master emits `heartbeat`.

Heartbeat data:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "generatedAt": "2026-08-04T12:00:00",
  "status": "RUNNING"
}
```

Stream completion data:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "generatedAt": "2026-08-04T12:00:05",
  "reason": "MAX_EVENTS_REACHED"
}
```

Security and safety:

- stream snapshots reuse the same safe admin DTOs as the polling endpoints,
- raw execution configuration snapshots are not exposed,
- raw merge plans are not exposed,
- API keys, password hashes, lease tokens, lease hashes, and `leaseExpiresAt` are not exposed,
- local filesystem paths, physical artifact paths, storage roots, artifact contents, stack traces, and internal storage keys are not exposed.

Browser note: native `EventSource` cannot set an `Authorization` header directly. A future admin UI may use a fetch-based SSE client/polyfill, or a cookie/session-based auth strategy if the security model is changed deliberately.

This stream is still live polling over derived read models, not a persistent audit log. If the client disconnects, missed intermediate derived states are not replayed from a stored event table.

## Group Artifacts

```http
GET /api/admin/execution-groups/{executionGroupId}/artifacts
```

Behavior:

- existing group returns a read-only discovery view for output artifacts attached to child executions,
- missing group returns `404`,
- no new artifacts are created,
- existing artifact upload and download semantics are unchanged,
- clients still download files with `GET /api/admin/artifacts/{artifactId}/download`,
- response metadata is safe and does not include raw configuration snapshots, raw merge plans, API keys, lease data, local filesystem paths, physical storage paths, or artifact contents.

Response shape:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "displayName": "S6 Happy Path Agent Merge",
  "status": "SUCCEEDED",
  "mergeMode": "AGENT",
  "failurePolicy": "FAIL_FAST",
  "artifactSummary": {
    "totalArtifacts": 10,
    "shardArtifacts": 8,
    "mergeArtifacts": 2,
    "shardsWithArtifacts": 4,
    "mergeHasArtifacts": true,
    "preferredOutputSource": "MERGE"
  },
  "shards": [
    {
      "shardIndex": 0,
      "shardCount": 4,
      "executionId": "00000000-0000-0000-0000-000000000000",
      "executionStatus": "SUCCEEDED",
      "workerId": "00000000-0000-0000-0000-000000000000",
      "workerHostname": "agent-01",
      "artifactCount": 2,
      "artifacts": [
        {
          "artifactId": "00000000-0000-0000-0000-000000000000",
          "executionId": "00000000-0000-0000-0000-000000000000",
          "groupRole": "SHARD",
          "shardIndex": 0,
          "relativePath": "result.json",
          "originalFilename": "result.json",
          "contentType": "application/json",
          "sizeBytes": 123,
          "createdAt": "2026-08-03T12:00:00"
        }
      ]
    }
  ],
  "merge": {
    "exists": true,
    "mergeExecutionCount": 1,
    "executionId": "00000000-0000-0000-0000-000000000000",
    "executionStatus": "SUCCEEDED",
    "workerId": "00000000-0000-0000-0000-000000000000",
    "workerHostname": "agent-02",
    "artifactCount": 2,
    "artifacts": [
      {
        "artifactId": "00000000-0000-0000-0000-000000000000",
        "executionId": "00000000-0000-0000-0000-000000000000",
        "groupRole": "MERGE",
        "shardIndex": null,
        "relativePath": "final-result.json",
        "originalFilename": "final-result.json",
        "contentType": "application/json",
        "sizeBytes": 123,
        "createdAt": "2026-08-03T12:01:00"
      }
    ]
  },
  "preferredOutputs": [
    {
      "artifactId": "00000000-0000-0000-0000-000000000000",
      "executionId": "00000000-0000-0000-0000-000000000000",
      "groupRole": "MERGE",
      "shardIndex": null,
      "relativePath": "final-result.json",
      "originalFilename": "final-result.json",
      "contentType": "application/json",
      "sizeBytes": 123,
      "createdAt": "2026-08-03T12:01:00"
    }
  ]
}
```

Output source rules:

- `preferredOutputSource = MERGE` when at least one merge artifact exists; `preferredOutputs` contains merge artifacts.
- `preferredOutputSource = SHARDS` when there are shard artifacts and no merge artifacts; `preferredOutputs` contains shard artifacts.
- `preferredOutputSource = NONE` when the group has no output artifacts; `preferredOutputs` is empty.
- `mergeMode = NONE` groups have no merge child and therefore no merge artifacts.
- Cancelled, failed, and partially failed groups can still expose partial shard or merge artifacts that were uploaded before terminal group status.

Sorting:

- `shards` are sorted by `shardIndex` ascending, nulls last, then execution id,
- artifacts inside each shard are sorted by `relativePath`, then `createdAt`, then artifact id,
- merge artifacts are sorted by `relativePath`, then `createdAt`, then artifact id,
- `preferredOutputs` uses the same artifact sorting as its source.

If more than one `MERGE` child exists because of a historical data issue, the endpoint remains safe: `merge.artifactCount` and `merge.artifacts` cover all merge children, `merge.mergeExecutionCount` reports how many merge children exist, and representative `merge.executionId`, `merge.executionStatus`, `merge.workerId`, and `merge.workerHostname` use the earliest merge child by `createdAt`, then execution id.

## Child Executions

```http
GET /api/admin/execution-groups/{executionGroupId}/executions
```

Behavior:

- existing group returns child executions,
- group with no children returns `[]`,
- missing group returns `404`,
- standalone executions are not returned,
- results are sorted deterministically as `SHARD` children first, then `MERGE` children; shards are ordered by `shardIndex` ascending, then `createdAt` ascending, then execution id ascending.

Response shape:

```json
[
  {
    "executionId": "00000000-0000-0000-0000-000000000000",
    "status": "ASSIGNED",
    "assignmentMode": "REQUIRE",
    "workerId": "00000000-0000-0000-0000-000000000000",
    "workerHostname": "agent-01",
    "groupRole": "SHARD",
    "shardIndex": 0,
    "shardCount": 4,
    "createdAt": "2026-07-21T10:00:00",
    "updatedAt": "2026-07-21T10:00:01",
    "completedAt": null
  }
]
```

`updatedAt` is derived from the newest known execution lifecycle timestamp because `work_executions` does not have a separate `updated_at` column.

## Existing Execution API Relationship

M17 extended existing admin execution list and detail responses with nullable group metadata:

```json
{
  "executionGroupId": null,
  "groupRole": null,
  "shardIndex": null,
  "shardCount": null
}
```

Standalone executions keep all group metadata as `null`. M18/M19 do not change standalone `POST /api/admin/executions` behavior.

Grouped child executions may have:

- `groupRole = SHARD`, `shardIndex = 0..shardCount-1`, `shardCount > 0`,
- `groupRole = MERGE`, `shardIndex = null`.

M18 creates only `SHARD` children. M19 creates one `MERGE` child for `mergeMode = AGENT` after the shard phase is ready. The `MERGE` child uses `groupRole = MERGE`, `shardIndex = null`, and the group shard count in `shardCount`.

The worker claim/report protocol is unchanged.

## Current Limitations

M28 does not implement:

- `mergeMode = MASTER`,
- background scheduler,
- Docker kill,
- Agent interrupt,
- retry or requeue policy,
- Agent changes,
- Docker executor changes,
- a persistent execution group event log or audit log,
- WebSocket artifact download,
- WebSocket workspace upload,
- WebSocket group creation,
- SOAP streaming,
- SOAP artifact download,
- SOAP workspace upload,
- SOAP group creation,
- SOAP binary or MTOM transfer,
- frontend UI,
- GPU support,
- Minecraft lifecycle, or research telemetry.

## Future Extensions

Future milestones may add background scheduling, Agent-side cooperative cancellation, Master-side merge for controlled formats, richer artifact aggregation and previews, selection diagnostics for groups, retry and requeue policies, frontend views, richer SOAP transfer modes, and persisted run evidence for protocol comparisons.
