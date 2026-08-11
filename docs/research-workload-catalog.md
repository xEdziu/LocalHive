# Research Workload Catalog

M29 adds a static, read-only research workload catalog for later benchmark and protocol comparison milestones. The catalog describes repeatable scenarios that M30 and M31 can use as benchmark inputs.

The catalog does not run workloads, create executions, create execution groups, persist benchmark metrics, schedule workers, upload workspace artifacts, or download output artifacts. Runtime smoke testing remains deferred until the combined post-M33 smoke stage.

## Purpose

The workload catalog gives research tooling a stable list of scenarios with:

- workload identity and human-readable name,
- workload type and complexity,
- expected execution shape,
- expected outcome,
- data profile requirements,
- recommended protocols and operations,
- Docker, workspace, merge, shard count, and timeout hints.

It is metadata only. It does not include raw executor configuration, merge plans, worker credentials, lease data, local paths, physical artifact paths, or internal storage keys.

## Endpoints

All endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

### List Catalog

```http
GET /api/admin/research/workload-catalog
```

Response shape:

```json
{
  "generatedAt": "2026-08-11T12:45:00",
  "workloads": [
    {
      "id": "NO_OP_TINY",
      "name": "NO_OP tiny job",
      "type": "NO_OP",
      "complexity": "TINY",
      "executionShape": "SINGLE_EXECUTION",
      "expectedOutcome": "SUCCEEDED",
      "dataProfiles": ["INLINE_ONLY"],
      "recommendedProtocols": ["REST"],
      "recommendedOperations": ["CREATE_SINGLE_EXECUTION", "GET_EXECUTION_STATUS"],
      "description": "Minimal baseline workload used to estimate protocol overhead without meaningful execution cost.",
      "researchPurpose": "Protocol overhead baseline.",
      "requiresDocker": false,
      "requiresWorkspaceArtifact": false,
      "requiresMerge": false,
      "suggestedShardCount": null,
      "suggestedTimeoutSeconds": 30,
      "tags": ["baseline", "tiny", "overhead"]
    }
  ]
}
```

### Get Workload

```http
GET /api/admin/research/workload-catalog/{workloadId}
```

Behavior:

- known workload id returns `200`,
- unknown workload id returns `404`.

### Validate Workload Combination

```http
POST /api/admin/research/workload-catalog/validate
```

Request:

```json
{
  "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
  "protocol": "REST",
  "operation": "CREATE_EXECUTION_GROUP",
  "dataTransferMode": "INLINE_JSON",
  "payloadFormat": "JSON"
}
```

Supported response:

```json
{
  "valid": true,
  "reasonCode": null,
  "reasonMessage": "Workload can be used with the selected protocol combination."
}
```

Unsupported response:

```json
{
  "valid": false,
  "reasonCode": "WORKLOAD_REQUIRES_WORKSPACE_ARTIFACT",
  "reasonMessage": "Workload requires a workspace artifact."
}
```

Validation first delegates to the existing research protocol contract validator. If the protocol, operation, transfer mode, and payload format combination is not supported by M26-M28 protocol contract rules, workload validation returns:

```json
{
  "valid": false,
  "reasonCode": "PROTOCOL_COMBINATION_NOT_SUPPORTED",
  "reasonMessage": "Protocol combination is not supported by the research protocol contract."
}
```

After the protocol combination is accepted, M29 applies workload rules:

- `SINGLE_EXECUTION` workloads reject execution-group-only operations,
- `EXECUTION_GROUP` and `EXECUTION_GROUP_WITH_AGENT_MERGE` workloads reject single-execution-only operations,
- workspace-required workloads reject creation combinations when the selected protocol has no workspace artifact flow,
- REST inline JSON creation remains valid for workspace workloads because the creation request references a previously uploaded workspace artifact id,
- read-only `GET_*` operations are allowed when the protocol contract supports them,
- non-recommended combinations are not automatically invalid unless they are impossible for the workload.

Stable workload validation reason codes:

- `SUPPORTED`,
- `UNKNOWN_WORKLOAD`,
- `PROTOCOL_COMBINATION_NOT_SUPPORTED`,
- `WORKLOAD_REQUIRES_WORKSPACE_ARTIFACT`,
- `WORKLOAD_REQUIRES_OUTPUT_ARTIFACTS`,
- `WORKLOAD_REQUIRES_STREAMING`,
- `WORKLOAD_REQUIRES_GROUP_OPERATION`,
- `WORKLOAD_REQUIRES_SINGLE_EXECUTION_OPERATION`,
- `WORKLOAD_REQUIRES_MERGE_SUPPORT`,
- `WORKLOAD_EXPECTS_FAILURE`,
- `WORKLOAD_EXPECTS_CANCEL`,
- `COMBINATION_NOT_RECOMMENDED`.

The valid response currently uses `reasonCode = null`, matching the existing protocol contract validation style. Malformed JSON or invalid enum values return `400` through the existing error handling path.

## Descriptor Fields

| Field | Description |
| --- | --- |
| `id` | Stable catalog id. |
| `name` | Human-readable workload label. |
| `type` | Stable workload type enum. |
| `complexity` | Expected rough size: `TINY`, `SMALL`, `MEDIUM`, or `LARGE`. |
| `executionShape` | `SINGLE_EXECUTION`, `EXECUTION_GROUP`, or `EXECUTION_GROUP_WITH_AGENT_MERGE`. |
| `expectedOutcome` | Expected terminal result: `SUCCEEDED`, `FAILED`, `CANCELLED`, or `PARTIALLY_FAILED`. |
| `dataProfiles` | Safe data profile metadata. |
| `recommendedProtocols` | Protocols that are useful for the scenario. They do not override protocol contract validation. |
| `recommendedOperations` | Operations that are useful for creating, observing, or controlling the scenario. |
| `description` | Short technical description. |
| `researchPurpose` | What the scenario is meant to measure or exercise. |
| `requiresDocker` | Whether the scenario is expected to require Docker execution. |
| `requiresWorkspaceArtifact` | Whether the scenario needs a pre-uploaded workspace package. |
| `requiresMerge` | Whether the scenario uses Agent merge. |
| `suggestedShardCount` | Suggested child execution count for grouped scenarios, otherwise `null`. |
| `suggestedTimeoutSeconds` | Suggested execution timeout for future benchmark setup. |
| `tags` | Small stable labels for grouping scenarios. |

## Workloads

| ID | Type | Complexity | Shape | Expected outcome | Data profile |
| --- | --- | --- | --- | --- | --- |
| `NO_OP_TINY` | `NO_OP` | `TINY` | `SINGLE_EXECUTION` | `SUCCEEDED` | `INLINE_ONLY` |
| `SMALL_JSON_ECHO` | `SMALL_JSON` | `SMALL` | `SINGLE_EXECUTION` | `SUCCEEDED` | `INLINE_ONLY` |
| `FILE_IO_SMALL` | `FILE_INPUT_OUTPUT` | `SMALL` | `SINGLE_EXECUTION` | `SUCCEEDED` | `WORKSPACE_ARTIFACT_REQUIRED`, `OUTPUT_ARTIFACTS_EXPECTED` |
| `SHARDED_OPTIMIZATION_4` | `SHARDED_OPTIMIZATION` | `MEDIUM` | `EXECUTION_GROUP` | `SUCCEEDED` | `WORKSPACE_ARTIFACT_REQUIRED`, `SHARDED_OUTPUTS_EXPECTED` |
| `AGENT_MERGE_OPTIMIZATION_4` | `AGENT_MERGE` | `MEDIUM` | `EXECUTION_GROUP_WITH_AGENT_MERGE` | `SUCCEEDED` | `WORKSPACE_ARTIFACT_REQUIRED`, `SHARDED_OUTPUTS_EXPECTED`, `MERGE_OUTPUTS_EXPECTED` |
| `LONG_RUNNING_SINGLE` | `LONG_RUNNING` | `MEDIUM` | `SINGLE_EXECUTION` | `SUCCEEDED` | `INLINE_ONLY` |
| `FAILING_TASK_SINGLE` | `FAILING_TASK` | `SMALL` | `SINGLE_EXECUTION` | `FAILED` | `INLINE_ONLY` |
| `CANCELLED_GROUP_QUEUED` | `CANCELLED_GROUP` | `SMALL` | `EXECUTION_GROUP` | `CANCELLED` | `INLINE_ONLY` |
| `MANY_SMALL_JOBS_20` | `MANY_SMALL_JOBS` | `MEDIUM` | `EXECUTION_GROUP` | `SUCCEEDED` | `INLINE_ONLY` |
| `FEW_HEAVY_JOBS_3` | `FEW_HEAVY_JOBS` | `LARGE` | `EXECUTION_GROUP` | `SUCCEEDED` | `INLINE_ONLY` |

## Relation To Protocol Contract

M29 reuses the existing protocol contract validator. Protocol status remains unchanged:

- `REST` is `AVAILABLE`,
- `WEBSOCKET` is `AVAILABLE`,
- `SOAP` is `AVAILABLE`.

The workload catalog does not add a new protocol, change adapter behavior, or extend operation support. It only describes which existing protocol combinations are usable for a selected workload.

## Relation To M30 And M31

M30 can use catalog descriptors to seed benchmark run metadata and metric recording.

M31 can use the workload validation endpoint to skip unsupported protocol combinations before protocol comparison runs.

M29 itself does not execute those runs.

## Security

Responses expose only safe workload metadata:

- ids, names, enum values, descriptions, purposes, booleans, suggestions, and tags,
- protocol, operation, data transfer mode, and payload format names,
- validation status and reason metadata.

Responses do not expose raw execution configuration, raw merge plans, API keys, password hashes, lease tokens, lease hashes, worker secrets, local filesystem paths, physical artifact paths, stack traces, or internal storage keys.

## Current Limitations

M29 intentionally does not implement:

- benchmark persistence,
- benchmark runner,
- workload execution automation,
- automatic execution creation from catalog descriptors,
- workspace artifact upload from the catalog,
- output artifact download from the catalog,
- runtime smoke tests,
- frontend UI,
- workload catalog editing,
- persistent catalog storage.

The next runtime smoke is planned as a larger combined smoke after M33.

## Future Extensions

Future milestones may add benchmark run records, protocol comparison runners, exported research datasets, fault injection scenarios, frontend workload selection, and richer catalog metadata.
