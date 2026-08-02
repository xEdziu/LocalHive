# Admin Execution Groups API

M17 added the read-only admin foundation for sharded workloads. M18 added the first create and event-driven scheduling foundation for Docker shard groups. M19 adds `mergeMode = AGENT`, where Master creates a normal Docker `MERGE` execution after the shard phase is ready. M20 adds admin group cancel and manual one-shot reconcile. M21 adds a derived observability summary to the group detail response and deterministic child execution ordering.

An `ExecutionGroup` is group-level metadata for sharding. Child work remains ordinary `WorkExecution` records with nullable group metadata. Master creates `SHARD` children, expands a controlled Docker command template for each shard, assigns as many shards as currently eligible workers allow, and schedules later waves when child executions report terminal status. With `mergeMode = AGENT`, Master later creates one `MERGE` child execution after shard policy allows merge.

The Agent does not know about sharding. Every shard is still claimed, leased, executed, and reported as a normal `WorkExecution`.

## Security

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Responses are safe admin summaries. They do not expose raw configuration snapshots, executor configuration, API keys, password hashes, lease token, lease token hash, `leaseExpiresAt`, storage paths, `dataRoot`, physical paths, artifact contents, or stack traces.

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

M21 does not implement:

- `mergeMode = MASTER`,
- background scheduler,
- Docker kill,
- Agent interrupt,
- retry or requeue policy,
- Agent changes,
- Docker executor changes,
- a separate execution group event log,
- frontend UI,
- GPU support,
- WebSocket, SOAP, Minecraft lifecycle, or research telemetry.

## Future Extensions

Future milestones may add background scheduling, Agent-side cooperative cancellation, Master-side merge for controlled formats, group artifact aggregation, selection diagnostics for groups, retry and requeue policies, and frontend views.
