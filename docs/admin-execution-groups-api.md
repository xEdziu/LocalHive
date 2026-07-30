# Admin Execution Groups API

M17 added the read-only admin foundation for sharded workloads. M18 adds the first create and event-driven scheduling foundation for Docker shard groups.

An `ExecutionGroup` is group-level metadata for sharding. Child work remains ordinary `WorkExecution` records with nullable group metadata. M18 creates `SHARD` children, expands a controlled Docker command template for each shard, assigns as many shards as currently eligible workers allow, and schedules later waves when child executions report terminal status.

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
- creates only `groupRole = SHARD` children,
- does not create a `MERGE` child execution,
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
| `mergeMode` | no | Defaults to `NONE`. Only `NONE` is supported in M18. `MASTER` and `AGENT` return `400`. |
| `failurePolicy` | no | Defaults to `FAIL_FAST`. `ALLOW_PARTIAL` is also supported for final group status derivation. |
| `assignmentMode` | no | Defaults to `AUTO`. `PREFER` is supported for the first shard. `REQUIRE` returns `400` in M18. |
| `workerId` | conditional | Must be absent for `AUTO`. Required for `PREFER`. |
| `configurationTemplate` | yes | Docker configuration template. It is validated with the existing Docker workload rules after command template expansion. |

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

## M18 Scheduling

Scheduling is event-driven in M18:

1. Group creation creates all child shards as queued executions.
2. Initial scheduling assigns as many queued shards as eligible workers allow.
3. When a child shard reaches a terminal status through the existing worker report flow, Master tries to assign the next queued shard.

Shard scheduling reuses M13 capability-aware worker selection. Eligibility still requires an approved, online, available worker with no active execution, enough shared RAM and CPU, a matching enabled executor capability, and a Docker policy that allows the requested image and resources.

More shards than workers are supported. If no worker is eligible during creation or after a child terminal report, queued shards remain `QUEUED` and the group remains safe to inspect through the read APIs. M18 does not include a background scheduler or manual reconcile endpoint, so a future worker becoming eligible does not automatically trigger group scheduling until another event triggers scheduling.

## M18 Group Status Derivation

For `mergeMode = NONE`:

- if any child is queued or non-terminal, the group is `RUNNING`,
- if all children are `SUCCEEDED`, the group is `SUCCEEDED`,
- if all children are terminal and at least one child failed, cancelled, or expired, `FAIL_FAST` derives `FAILED`,
- if all children are terminal, at least one child succeeded, and `failurePolicy = ALLOW_PARTIAL`, the group derives `PARTIALLY_FAILED`.

M18 does not aggressively interrupt or kill running children for `FAIL_FAST`. It also preserves future cancellation and expiry states if such a state is already present.

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
  "failureMessage": null
}
```

`activeExecutions` counts `ASSIGNED`, `CLAIMED`, and `RUNNING` children. `terminalExecutions` counts `SUCCEEDED`, `FAILED`, `CANCELLED`, and `EXPIRED` children. `QUEUED` children are counted in `totalExecutions` and `childExecutionCounts`, but they are not active or terminal.

## Child Executions

```http
GET /api/admin/execution-groups/{executionGroupId}/executions
```

Behavior:

- existing group returns child executions,
- group with no children returns `[]`,
- missing group returns `404`,
- standalone executions are not returned.

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

Standalone executions keep all group metadata as `null`. M18 does not change standalone `POST /api/admin/executions` behavior.

Grouped child executions may have:

- `groupRole = SHARD`, `shardIndex = 0..shardCount-1`, `shardCount > 0`,
- `groupRole = MERGE`, `shardIndex = null`.

M18 creates only `SHARD` children. `MERGE` is a persisted model value for future merge execution support.

The worker claim/report protocol is unchanged.

## Current Limitations

M18 does not implement:

- merge/reduce,
- `groupRole = MERGE` creation,
- manual reconcile endpoint,
- background scheduler,
- group cancel,
- Agent changes,
- Docker executor changes,
- frontend UI,
- GPU support,
- WebSocket, SOAP, Minecraft lifecycle, or research telemetry.

## Future Extensions

Future milestones may add manual reconcile, background scheduling, group cancellation, merge execution, group artifact aggregation, selection diagnostics for groups, retry and requeue policies, and frontend views.
