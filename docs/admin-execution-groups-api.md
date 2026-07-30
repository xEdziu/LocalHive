# Admin Execution Groups API

M17 adds the read-only admin foundation for future sharded workloads.

An `ExecutionGroup` is group-level metadata for future sharding. Child work remains ordinary `WorkExecution` records with nullable group metadata. M17 does not create sharded executions, schedule shards, merge outputs, reconcile groups, or cancel groups.

## Security

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Responses are safe admin summaries. They do not expose raw configuration snapshots, executor configuration, API keys, password hashes, lease token, lease token hash, `leaseExpiresAt`, storage paths, `dataRoot`, physical paths, artifact contents, or stack traces.

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
      "status": "CREATED",
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
  "status": "CREATED",
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

M17 extends existing admin execution list and detail responses with nullable group metadata:

```json
{
  "executionGroupId": null,
  "groupRole": null,
  "shardIndex": null,
  "shardCount": null
}
```

Standalone executions keep all group metadata as `null`.

Grouped child executions may have:

- `groupRole = SHARD`, `shardIndex = 0..shardCount-1`, `shardCount > 0`,
- `groupRole = MERGE`, `shardIndex = null`.

The worker claim/report protocol is unchanged.

## Current Limitations

M17 does not implement:

- `POST /api/admin/execution-groups`,
- automatic child execution creation,
- shard scheduling or reconciliation,
- merge/reduce,
- group cancel,
- Agent changes,
- Docker executor changes,
- frontend UI,
- GPU support,
- WebSocket, SOAP, Minecraft lifecycle, or research telemetry.

## Future Extensions

Future milestones may add sharded create, wave scheduling, manual reconcile, group cancellation, merge execution, group artifact aggregation, selection diagnostics for groups, and frontend views.
