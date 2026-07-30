# Admin Execution Cancel API

M14 adds the first production admin endpoint for cancelling an execution before real worker-side execution begins.

This is a Master-side cancellation foundation. It updates the `WorkExecution` record and does not send a command to the Agent, interrupt a running Agent task, kill a Docker container, or change worker pause/resume state. Master does not provide an admin worker pause/resume API; pause and resume remain local Agent controls.

## Endpoint

```http
POST /api/admin/executions/{executionId}/cancel
Authorization: Bearer <ADMIN_JWT>
Content-Type: application/json
Accept: application/json
```

Request body is optional:

```json
{
  "reason": "Smoke cancellation before claim"
}
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

## Response

The endpoint returns the existing safe admin execution detail response. On success, the execution status becomes `CANCELLED`.

The response may include the cancellation through the existing failure fields:

```json
{
  "status": "CANCELLED",
  "failure": {
    "code": "ADMIN_CANCELLED",
    "message": "Smoke cancellation before claim"
  }
}
```

The admin execution detail response does not expose:

- raw config,
- requested configuration snapshot,
- resolved configuration snapshot,
- executor configuration,
- API key,
- API key hash,
- password hash,
- lease token,
- lease token hash,
- `leaseExpiresAt`,
- storage path,
- `dataRoot`,
- physical paths,
- stack traces or internal exception details.

## Cancellation Rules

Allowed transitions:

| Current status | Result |
| --- | --- |
| `ASSIGNED` | `CANCELLED` |
| `QUEUED` | `CANCELLED` |

Rejected with `409 Conflict`:

- `CLAIMED`,
- `RUNNING`,
- `SUCCEEDED`,
- `FAILED`,
- `CANCELLED`,
- `EXPIRED`.

Unknown execution ids return `404`. Cancelling an already terminal execution is rejected, not treated as a successful idempotent operation.

`CLAIMED` and `RUNNING` are intentionally rejected because M14 does not interrupt Agent execution. Docker kill or process interruption is not part of M14.

For a successful cancellation, Master updates the execution status, cancellation timestamp, completion timestamp, and cancellation failure fields. Existing assignment history remains in place.

## Cancellation Reason

Rules:

- request body is optional,
- missing body uses `Execution cancelled by admin.`,
- blank `reason` uses `Execution cancelled by admin.`,
- nonblank `reason` is trimmed,
- maximum reason length is 500 characters,
- a reason over the maximum returns `400 Bad Request`,
- cancellation code is `ADMIN_CANCELLED`.

The reason is safe display text. It must not be used as a path, shell command, Docker argument, storage key, authorization input, or lease input.

M14 does not persist admin username, admin id, or a full audit trail for cancellation.

## Claim Behavior

A cancelled execution is terminal.

Worker claim-next does not return `CANCELLED` executions. If an assigned execution is cancelled before claim, that worker can still claim another assigned execution later when it is eligible.

The worker claim, lease, running report, terminal report, and artifact upload contracts are unchanged.

## Side Effects

Allowed side effect:

- update `WorkExecution` status, timestamps, failure code, and failure message.

M14 does not:

- create a new `WorkExecution`,
- create a new `WorkInstance`,
- create a new `ExecutionAssignment`,
- delete an assignment,
- delete artifacts,
- mutate worker approval, connection, or availability state,
- mutate worker capabilities,
- mutate `WorkDefinition` or `WorkDefinitionVersion`,
- create an `AgentCommand`,
- kill Docker,
- mutate storage or setup configuration.

## Database / Migration

M14 adds:

```text
V12__allow_admin_cancelled_execution_reason.sql
```

V1-V11 remain unchanged.

V12 adjusts `work_executions` constraints so an admin-style cancelled execution can store `completed_at`, `cancelled_at`, `ADMIN_CANCELLED`, and the cancellation message. The migration preserves compatibility with older cancellation semantics where a cancelled execution may have no failure fields.

No new table is added.

## Admin API Compatibility

The read-only admin execution endpoints naturally show cancelled executions:

```http
GET /api/admin/executions
GET /api/admin/executions/{executionId}
```

Output artifact APIs, selection diagnostics, and create execution behavior are otherwise unchanged. Cancelled executions are terminal and are not claimable by the worker API.

## Manual Smoke Notes

To smoke cancellation before claim:

1. Create an execution with `REQUIRE` or `AUTO`.
2. Cancel while the execution is still `ASSIGNED`.
3. Inspect the detail response and verify `CANCELLED`.
4. Call worker claim-next and verify the cancelled execution is not returned.
5. Try cancelling a terminal execution and expect `409 Conflict`.

If an Agent is polling too quickly, use an offline worker or stop the Agent before creating the execution to keep it cancellable. Do not use Master worker pause/resume; it does not exist by design.

## Current Limitations

- no cancellation of `CLAIMED` or `RUNNING`,
- no Agent-side interruption,
- no Docker kill,
- no graceful cancel command,
- no cancellation queue or outbox,
- no frontend UI,
- no audit trail or admin identity persistence,
- no cascade cancel for parent or child executions because sharding does not exist yet.

## Future Extensions

Future work may add:

- Agent-side cooperative cancellation,
- running execution interruption after explicit protocol design,
- Docker process cleanup after explicit runtime design,
- cancellation audit trail,
- frontend cancellation action,
- retry and requeue flows,
- parent or child execution cancellation after sharding exists.

The proposed group cancellation direction is documented in [Sharding ADR](architecture/sharding-adr.md).
