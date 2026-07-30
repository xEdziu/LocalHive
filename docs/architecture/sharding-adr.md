# Sharding ADR

## Status

Proposed

M17 implementation note: the domain foundation now includes `ExecutionGroup` persistence, nullable group metadata on `WorkExecution`, and read-only admin group APIs. Sharded creation, scheduling, reconciliation, merge/reduce, group cancellation, Agent changes, and frontend UI remain future work.

## Context

LocalHive already has production foundations for Docker workload execution:

- Master can create `WorkExecution` records from approved Docker Work Definition Versions.
- Master can assign work to workers with `REQUIRE`, `AUTO`, and `PREFER`.
- M13 capability-aware worker selection can choose workers by approval, connection, availability, active execution state, resources, executor capability, and Docker policy fit.
- Agent claims ordinary `WorkExecution` records through the existing worker claim, lease, running report, terminal report, workspace, and output artifact APIs.
- M15 confirmed a simple optimization workload as a Docker workload with a workspace artifact and output artifacts.

The next architectural step is splitting a larger workload into many shards. Sharding should not require the Agent to understand sharding. Master should manage the group, planning, scheduling, cancellation state, and future merge/reduce behavior, while Agents continue to execute ordinary child `WorkExecution` records.

## Goals

- Split larger work into multiple child executions.
- Reuse existing `WorkExecution` as the shard execution unit.
- Preserve the existing Agent claim, lease, report, workspace, and output artifact flow.
- Reuse M13 capability-aware worker selection for shard assignment.
- Support more shards than currently eligible workers.
- Prepare for future merge/reduce.
- Keep cancellation aligned with M14.
- Prepare data and APIs for a future admin panel.
- Keep API and response DTOs safe by default.

## Non-goals

M16 does not implement:

- Agent changes,
- Docker kill or process interrupt,
- interruption of `RUNNING` executions,
- full retry or requeue policy,
- GPU support,
- frontend UI,
- a concrete merge algorithm,
- Python, C++, or Java runner image expansion,
- WebSocket or SOAP transport,
- Minecraft workload lifecycle,
- research telemetry.

M16 also does not add entities, migrations, endpoints, a scheduler, a reconciler, merge execution, or sharded execution creation.

## Decisions

### Decision 1 - Shard Is WorkExecution

A shard is a normal `WorkExecution`.

Consequences:

- Agent does not need a new contract.
- Each shard has its own assignment, claim, lease, report, and terminal lifecycle.
- Each shard can have its own output artifacts.
- Each shard has its own status.
- Master can reuse current admin execution list, detail, and artifact APIs for child executions.
- Existing worker API authentication and lease validation stay unchanged.

### Decision 2 - Parent Is ExecutionGroup

The parent concept is named `ExecutionGroup`.

`ExecutionGroup` groups child executions and stores group-level metadata, status, progress, and future cancellation, reconciliation, and merge state.

The model should not be called `WorkBatch`.

### Decision 3 - ExecutionGroup Statuses

The group status model should include:

```text
CREATED
SCHEDULING
RUNNING
MERGING
SUCCEEDED
PARTIALLY_FAILED
FAILED
CANCELLING
CANCELLED
EXPIRED
```

Meaning:

| Status | Meaning |
| --- | --- |
| `CREATED` | Group metadata exists, but child scheduling has not started. |
| `SCHEDULING` | Master is creating or assigning child executions. |
| `RUNNING` | At least one shard is assigned, claimed, or running, and group work is not terminal. |
| `MERGING` | Shards reached a state where merge/reduce work is being performed or scheduled. |
| `SUCCEEDED` | Group completed according to its success and merge policy. |
| `PARTIALLY_FAILED` | Some shards failed or expired, but group policy allows a partial result. |
| `FAILED` | Group cannot produce an acceptable result. |
| `CANCELLING` | Group cancellation was requested, but active children still need to reach terminal states. |
| `CANCELLED` | Cancellation completed according to the group cancellation policy. |
| `EXPIRED` | Group exceeded a future expiry policy. |

`PARTIALLY_FAILED` should exist from the beginning. Group status is derived from child executions and merge state. Exact transition implementation can be defined in M17/M18.

### Decision 4 - Child Metadata

Child `WorkExecution` records should carry group metadata:

```text
executionGroupId
groupRole
shardIndex
shardCount
```

`groupRole` values:

```text
SHARD
MERGE
```

Rules:

- `SHARD` child executions use `shardIndex` from `0` to `shardCount - 1`.
- `shardCount` is the same for all shard executions in one group.
- `MERGE` execution may use `shardIndex = null`, unless implementation later chooses a stricter representation.
- Standalone executions have `executionGroupId`, `groupRole`, `shardIndex`, and `shardCount` set to null.

### Decision 5 - Command Template

Sharding uses a command template with controlled placeholders:

```json
{
  "commandTemplate": [
    "sh",
    "/workspace/optimize.sh",
    "{{shardIndex}}",
    "{{shardCount}}"
  ]
}
```

Master expands the template into concrete child execution commands:

```json
["sh", "/workspace/optimize.sh", "0", "4"]
["sh", "/workspace/optimize.sh", "1", "4"]
```

Rules:

- Placeholder substitution must be controlled by Master validation.
- Unsupported placeholders fail validation.
- Substitution does not invoke shell interpolation by itself.
- The final Docker command remains a list of strings.
- Admin UI should not require manual editing of `shardIndex` or `shardCount` per shard.

Future environment variables are possible but not required now:

```text
LOCALHIVE_SHARD_INDEX
LOCALHIVE_SHARD_COUNT
LOCALHIVE_EXECUTION_GROUP_ID
```

### Decision 6 - Merge / Reduce Model

Merge should be modeled from the beginning, even if the first implementation supports only `NONE`.

Merge modes:

```text
NONE
MASTER
AGENT
```

Meaning:

| Mode | Meaning |
| --- | --- |
| `NONE` | No automatic merge. Each shard leaves its own output artifacts. |
| `MASTER` | Master performs merge only for supported, controlled formats or reducers. |
| `AGENT` | Master creates an additional `WorkExecution` with `groupRole = MERGE`. |

For `AGENT` merge:

- merge is a normal Docker workload,
- merge receives workspace plus shard data or artifacts,
- merge writes final output to `/output`,
- Agent still does not need to understand merge beyond normal execution.

Merge worker policy direction:

```text
AUTO
PREFER
REQUIRE
FASTEST_SHARD_WORKER_AS_PREFERRED
BEST_RESOURCES
```

These policies are design direction only in M16.

### Decision 7 - More Shards Than Workers

An `ExecutionGroup` can contain more shards than currently eligible workers.

Rules:

- Child shard executions can start as `QUEUED`.
- Master assigns as many shards as eligible worker capacity allows.
- When a worker finishes a shard, Master can assign another queued shard.
- One active execution per worker remains the default scheduling rule.
- This requires scheduling or reconciliation in implementation.

The final design should not use `shardCount <= eligible workers` as a permanent limit.

### Decision 8 - Scheduling / Reconciliation

Scheduling should run:

- when an `ExecutionGroup` is created,
- after a child execution reaches a terminal state,
- optionally through an admin/manual reconcile endpoint later.

Scheduling purpose:

- assign queued shards to eligible workers,
- respect one active execution per worker,
- use M13 capability-aware selection,
- avoid assigning multiple active shards to the same worker in one scheduling pass.

No background scheduler is required in the first implementation unless later decided.

Possible future endpoint:

```http
POST /api/admin/execution-groups/{executionGroupId}/reconcile
```

### Decision 9 - Worker Selection For Shards

Each shard uses existing M13 capability-aware selection:

- worker is approved,
- worker is online,
- worker is available,
- worker has no active execution,
- hardware resource fit passes,
- matching executor capability exists,
- Docker policy fit passes,
- deterministic scoring chooses among candidates.

For group scheduling, each assignment should account for workers already selected for active shards in the same scheduling pass. Master should avoid assigning two active child executions to one worker.

### Decision 10 - Cancellation Model

`ExecutionGroup` cancellation follows M14 principles:

- `QUEUED` and `ASSIGNED` child executions can become `CANCELLED`.
- `CLAIMED` and `RUNNING` child executions are not interrupted in V1.
- No Docker kill is performed.
- No Agent interrupt is sent.
- Group may enter `CANCELLING` until active children finish.
- Final group status may become `CANCELLED`, `PARTIALLY_FAILED`, or another terminal state depending on child results and policy.

Full group cancellation implementation is future work.

### Decision 11 - API Direction

Use `ExecutionGroup` in API names.

Proposed admin endpoints:

```http
POST /api/admin/execution-groups
GET  /api/admin/execution-groups
GET  /api/admin/execution-groups/{executionGroupId}
GET  /api/admin/execution-groups/{executionGroupId}/executions
```

Future admin endpoints:

```http
POST /api/admin/execution-groups/{executionGroupId}/cancel
POST /api/admin/execution-groups/{executionGroupId}/reconcile
GET  /api/admin/execution-groups/{executionGroupId}/artifacts
```

API rules:

- admin-only,
- safe responses by default,
- no raw config,
- no raw snapshots,
- no secrets,
- no lease fields,
- no physical paths.

### Decision 12 - Admin UI Direction

Future admin UI should expose:

- enable sharding,
- shard count,
- command template,
- merge mode,
- merge worker policy,
- status and progress,
- child execution list,
- output artifacts.

The UI should not require manual editing of each shard command.

## ExecutionGroup Model

Initial group metadata should be enough to create, inspect, schedule, and later reconcile a group:

- group id,
- display name,
- Work Definition Version,
- group status,
- shard count,
- command template,
- merge mode,
- failure policy,
- created timestamp,
- started or scheduled timestamp if needed,
- completed timestamp if needed,
- cancellation timestamp if needed.

Exact persistence fields belong to M17. The M16 decision is the boundary: group-level state belongs to `ExecutionGroup`, while executable units remain child `WorkExecution` records.

## Child WorkExecution Model

Child executions should remain ordinary `WorkExecution` records with additional nullable group metadata:

- `executionGroupId`,
- `groupRole`,
- `shardIndex`,
- `shardCount`.

Standalone execution behavior must remain unchanged when these fields are null.

`SHARD` children carry concrete Docker configuration after command template expansion. The Agent claims and runs them as normal Docker workload executions.

`MERGE` child execution, when introduced, should also be a normal `WorkExecution`.

## Shard Command Template

The group create request should accept one command template, not a manually expanded command for every shard.

Example template:

```json
[
  "sh",
  "/workspace/optimize.sh",
  "{{shardIndex}}",
  "{{shardCount}}"
]
```

For `shardCount = 4`, Master creates child commands:

```json
["sh", "/workspace/optimize.sh", "0", "4"]
["sh", "/workspace/optimize.sh", "1", "4"]
["sh", "/workspace/optimize.sh", "2", "4"]
["sh", "/workspace/optimize.sh", "3", "4"]
```

The substitution layer should operate on list elements and must not concatenate a shell command string. If a template uses `sh -c`, shell behavior is still the workload author's responsibility, but LocalHive should not add shell interpolation.

## Scheduling / Reconciliation

The first scheduling foundation can be event-driven:

1. Group creation creates N queued child executions.
2. Initial scheduling assigns as many child executions as eligible workers allow.
3. When a child reaches terminal state, reconciliation assigns more queued children if capacity exists.
4. Optional manual reconcile can repair missed scheduling events.

The scheduler should preserve one active execution per worker and should use the same worker eligibility semantics as M13.

No background daemon is required as a first decision. A background scheduler may be added later if event-driven and manual reconcile are not enough.

## Merge / Reduce Model

Merge should be explicit group metadata.

`NONE` is the simplest first implementation and leaves every shard output visible independently.

`MASTER` is appropriate only for controlled reducers that Master can safely parse and execute without arbitrary user code. It should not become a general script execution mechanism inside Master.

`AGENT` is the preferred future general-purpose reducer model. Master creates a normal Docker workload execution with `groupRole = MERGE`, waits for shard artifacts, provides needed inputs, and collects the final output artifact through the existing Agent output upload path.

## Status Model

Group status is derived from:

- child execution statuses,
- scheduling state,
- cancellation request state,
- merge state,
- failure policy.

Potential policy fields:

```text
failurePolicy:
- FAIL_FAST
- ALLOW_PARTIAL

mergeMode:
- NONE
- MASTER
- AGENT
```

`PARTIALLY_FAILED` is meaningful especially with `ALLOW_PARTIAL`.

`FAIL_FAST` may cancel queued and assigned shards when one shard fails. Running shards still are not killed in V1.

## Cancellation Model

Group cancellation should not exceed M14 semantics in V1.

When group cancel is requested:

- queued children can be marked `CANCELLED`,
- assigned children can be marked `CANCELLED`,
- claimed/running children continue until terminal report,
- no Docker kill occurs,
- no Agent interrupt occurs,
- group remains `CANCELLING` until policy can derive a terminal group status.

Future Agent-side cooperative cancellation requires a separate worker protocol design.

## API Direction

The first API should be admin-only and production-oriented.

Suggested create request shape should include:

- Work Definition Version id,
- display name,
- shard count,
- assignment mode or scheduling mode,
- command template,
- workspace artifact reference,
- resource request,
- merge mode,
- failure policy.

Responses must be safe summaries. Raw configuration snapshots, executor configuration, API keys, password hashes, lease token, lease token hash, `leaseExpiresAt`, storage path, `dataRoot`, physical paths, and stack traces must not be returned.

## Admin UI Direction

Future UI should treat sharding as one higher-level work request. It should show:

- group status,
- progress counters,
- child execution statuses,
- selected workers,
- failed shard reasons,
- output artifact availability,
- merge status.

The UI should generate shard arguments from template and shard count. It should not ask the admin to hand-author one command per shard.

## Security / Safety

- `ExecutionGroup` APIs are admin-only.
- Child executions still use worker API key plus execution lease as before.
- No new Agent secrets are introduced.
- Raw config and raw snapshots are not exposed by admin group APIs.
- Physical paths are not exposed.
- Command template substitution is controlled and validates supported placeholders.
- Template substitution does not use shell concatenation.
- Workspace remains read-only unless the existing executor configuration explicitly allows otherwise.
- Output artifacts continue through existing safe output artifact APIs.
- Merge execution should not allow Master to run arbitrary user code inside the Master process.

## Compatibility With Existing Milestones

| Milestone | Compatibility |
| --- | --- |
| M13 capability-aware worker selection | Reused for shard assignment. |
| M13.1 selection diagnostics | Can be extended later for group and shard diagnostics. |
| M14 admin execution cancel | Reused as the principle for cancelling queued or assigned children. |
| M15 simple optimization workload | First candidate workload to shard. |

The Agent remains compatible because every shard is still a normal claimed `WorkExecution`.

## Current Limitations

- No sharded execution creation exists yet.
- No sharded create endpoint exists yet.
- No scheduling or reconciliation code exists yet.
- No merge execution exists yet.
- No group cancel endpoint exists yet.
- No Agent changes exist or are required for the proposed shard model.
- No frontend UI exists.
- No GPU support is introduced.
- No WebSocket, SOAP, Minecraft lifecycle, or research telemetry is introduced.

## Future Extensions

- Group-level diagnostics before creation.
- Capability-aware wave scheduling UI.
- Agent-side cooperative cancellation.
- Retry and requeue policies.
- Merge worker policy implementation.
- `AGENT` merge execution with final result artifact.
- Cursor-paginated group history.
- Shard progress and log summaries.
- GPU-aware shard scheduling after explicit GPU support design.
- Workload-specific templates after the generic model is stable.

## Open Questions

- Should group creation create all child executions immediately or create them lazily in waves?
- Should `ExecutionGroup` store requested configuration snapshot, resolved child template, or both?
- What is the first supported merge mode: `NONE` only, or `AGENT` behind a narrow contract?
- How should shard output artifacts be made available to an `AGENT` merge execution?
- Should group cancellation use `FAIL_FAST` and `ALLOW_PARTIAL` policies, or a separate cancellation policy?
- How should group-level progress be counted when merge execution exists?
- Is manual reconcile enough for V1, or is a lightweight background scheduler required?
- Should group diagnostics be an extension of M13.1 or a separate endpoint?

## Implementation Plan

### M17 - ExecutionGroup Domain Foundation

- Add entity, migration, and enums.
- Add child metadata on `WorkExecution`.
- Add admin read APIs.
- Do not add automatic sharded create yet.

### M18 - Sharded Execution Create + Scheduling Foundation

- Add `POST /api/admin/execution-groups`.
- Create N child executions.
- Assign eligible shards in waves.
- Start with `mergeMode = NONE` unless `AGENT` merge is explicitly scoped.

### M19 - Agent/Master Merge Execution Foundation

- Add `mergeMode = AGENT`.
- Create `MERGE` execution after shards finish.
- Produce final result artifact.

### M20 - ExecutionGroup Cancel / Reconcile

- Add group cancel.
- Add manual reconcile.
- Harden status derivation and edge cases.
