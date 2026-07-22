# Admin Execution API

M7 adds production admin endpoints for browsing Work Executions.

Dev-smoke endpoints create local test executions, and the worker API handles claim, lease, running, terminal report, and artifact upload flows. Those APIs are intentionally operational. A future Master frontend and current admin tooling need a separate read-only surface for inspecting execution history without using worker protocol endpoints or exposing runtime secrets.

The read side of the Admin Execution API provides:

- a paginated execution list,
- an execution detail view,
- links to the existing output artifact metadata and download endpoints.

The list and detail endpoints documented here do not mutate execution state.

Executions can now be created from approved Work Definition Versions through the [Admin Create Execution API](admin-create-execution-api.md). M11 [Worker Selection](worker-selection.md) documents how `REQUIRE`, `AUTO`, and `PREFER` choose the assigned worker. M9 adds a read-only [Admin Work Definition API](admin-work-definition-api.md) for browsing definitions and versions before creating an execution.

M14 adds a separate [Admin Execution Cancel API](admin-execution-cancel-api.md) for cancelling `QUEUED` or `ASSIGNED` executions before real Agent-side execution begins.

## List Executions

```http
GET /api/admin/executions
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.

Query parameters:

| Parameter | Required | Description |
| --- | --- | --- |
| `limit` | no | Page size. Defaults to `50`; maximum is `200`. |
| `offset` | no | Zero-based offset. Defaults to `0`. |
| `status` | no | Exact `WorkExecutionStatus` enum value, for example `SUCCEEDED`, `FAILED`, or `RUNNING`. |
| `workerId` | no | Worker UUID filter. |

Behavior:

- results are newest-first,
- pagination is offset-based,
- `offset` applies after filtering and sorting,
- empty pages return `items: []`,
- invalid `limit` returns `400`,
- invalid `offset` returns `400`,
- invalid `status` returns `400`,
- invalid `workerId` returns `400`,
- an unknown but valid `workerId` returns `items: []` and `totalCount: 0`.

Response shape:

```json
{
  "items": [
    {
      "executionId": "00000000-0000-0000-0000-000000000000",
      "displayName": "M6.1 Storage Smoke",
      "status": "SUCCEEDED",
      "executorId": "localhive.docker.workload",
      "executorContractVersion": 1,
      "workDefinitionLogicalId": "localhive.docker.workload",
      "workDefinitionVersion": 1,
      "workerId": "00000000-0000-0000-0000-000000000000",
      "workerHostname": "AMG-NORTH",
      "createdAt": "2026-07-19T19:21:57.681129",
      "assignedAt": "2026-07-19T19:21:57.685132",
      "claimedAt": "2026-07-19T19:22:05.126633",
      "startedAt": "2026-07-19T19:22:05.235862",
      "completedAt": "2026-07-19T19:22:05.869497",
      "durationMs": 633,
      "outputArtifactCount": 1
    }
  ],
  "limit": 50,
  "offset": 0,
  "totalCount": 1
}
```

`limit=200` is the maximum page size, not a full history limit. Use `offset` to access older execution history.

Example:

```http
GET /api/admin/executions?status=SUCCEEDED&limit=20&offset=0
Accept: application/json
Authorization: Bearer {{auth_token}}
```

## Execution Detail

```http
GET /api/admin/executions/{executionId}
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.

Behavior:

- an existing execution returns `200`,
- a missing execution returns `404`,
- an invalid UUID uses the current framework validation/error response behavior.

Response shape:

```json
{
  "executionId": "00000000-0000-0000-0000-000000000000",
  "displayName": "M6.1 Storage Smoke",
  "status": "SUCCEEDED",
  "executorId": "localhive.docker.workload",
  "executorContractVersion": 1,
  "workDefinition": {
    "definitionId": "00000000-0000-0000-0000-000000000000",
    "definitionVersionId": "00000000-0000-0000-0000-000000000000",
    "logicalId": "localhive.docker.workload",
    "version": 1,
    "name": "Docker Workload"
  },
  "workInstance": null,
  "assignment": {
    "assignmentId": "00000000-0000-0000-0000-000000000000",
    "workerId": "00000000-0000-0000-0000-000000000000",
    "workerHostname": "AMG-NORTH",
    "mode": "REQUIRE",
    "assignedAt": "2026-07-19T19:21:57.685132",
    "claimedAt": "2026-07-19T19:22:05.126633"
  },
  "timing": {
    "createdAt": "2026-07-19T19:21:57.681129",
    "queuedAt": "2026-07-19T19:21:57.681129",
    "assignedAt": "2026-07-19T19:21:57.685132",
    "claimedAt": "2026-07-19T19:22:05.126633",
    "startedAt": "2026-07-19T19:22:05.235862",
    "completedAt": "2026-07-19T19:22:05.869497",
    "cancelledAt": null,
    "expiredAt": null,
    "durationMs": 633
  },
  "artifacts": {
    "outputArtifactCount": 1
  },
  "failure": {
    "code": null,
    "message": null
  }
}
```

The detail response intentionally does not include `leaseExpiresAt`. It is technical worker claim/lease protocol metadata and is not part of the admin execution response. Raw configuration snapshots are also not exposed by this API.

## Artifact Endpoint Relationship

The execution list and detail endpoints expose only `outputArtifactCount`.

Execution creation is documented separately in [Admin Create Execution API](admin-create-execution-api.md). The assigned worker can come from `REQUIRE`, `AUTO`, or `PREFER`; selection details are documented in [Worker Selection](worker-selection.md). The create response is also a safe summary and does not expose raw configuration, lease fields, or storage paths.

Existing artifact endpoints provide the output artifact metadata and download flow:

```http
GET /api/admin/executions/{executionId}/artifacts
GET /api/admin/artifacts/{artifactId}/download
```

The artifact list endpoint returns metadata for output artifacts. The download endpoint streams one `EXECUTION_OUTPUT` artifact. Artifact APIs do not expose the physical storage path, and physical artifact storage remains internal to Master.

Execution cancellation is documented separately in [Admin Execution Cancel API](admin-execution-cancel-api.md). Cancellation does not change output artifact endpoints and does not delete existing artifact metadata.

Example artifact list response:

```json
[
  {
    "artifactId": "00000000-0000-0000-0000-000000000000",
    "kind": "EXECUTION_OUTPUT",
    "executionId": "00000000-0000-0000-0000-000000000000",
    "uploadedByWorkerId": "00000000-0000-0000-0000-000000000000",
    "relativePath": "results/m61-output.txt",
    "originalFilename": "m61-output.txt",
    "contentType": "application/octet-stream",
    "sizeBytes": 63,
    "sha256": "abcdef...",
    "createdAt": "2026-07-19T19:22:05.761713"
  }
]
```

## Security / Non-Exposed Fields

The admin execution list, detail, and cancel responses do not expose:

- API key,
- API key hash,
- password hash,
- lease token,
- lease token hash,
- `leaseExpiresAt`,
- raw execution lease fields,
- `requestedConfigurationSnapshot`,
- `resolvedConfigurationSnapshot`,
- `storagePath`,
- `dataRoot`,
- physical absolute storage path,
- artifact file contents,
- stack traces.

Failure messages are defensively normalized, redacted, and truncated. Raw diagnostic detail may be intentionally reduced before it is returned to an admin client.

Artifact file contents are not embedded in list/detail or metadata responses. They are returned only by the explicit artifact download endpoint.

## Pagination Model

M7 uses offset-based pagination for simplicity.

- `limit` controls page size.
- `offset` selects the zero-based result offset after filters and newest-first sorting.
- `totalCount` respects active filters.

Future cursor pagination may be introduced if execution history grows substantially.

## Filtering

The `status` filter is an exact enum value, such as `SUCCEEDED`, `FAILED`, or `RUNNING`.

The `workerId` filter is a UUID. Filters affect both `items` and `totalCount`.

The [Admin Worker API](admin-worker-api.md) exposes a `recentExecutions` preview with at most 5 newest executions for one worker. Use this endpoint with the `workerId` filter when an admin client needs the full paginated history for that worker:

```http
GET /api/admin/executions?workerId={workerId}&limit=50&offset=0
```

## Duration

`durationMs` is calculated from `startedAt` and `completedAt`. If either timestamp is missing, `durationMs` may be `null`. Master does not invent a fake duration for incomplete or partially recorded executions.

## Current Limitations

- list and detail endpoints are read-only,
- cancellation is limited to `QUEUED` and `ASSIGNED` through the separate cancel endpoint,
- no cancellation of `CLAIMED` or `RUNNING`,
- no Agent-side interruption or Docker kill,
- no retry or requeue endpoints,
- no date-range filtering,
- no text search,
- no cursor pagination yet,
- no full frontend UI yet,
- no raw config display,
- no artifact content inline preview,
- no cleanup or retention policy.

## Future Extensions

Future work may add:

- Master frontend execution table,
- execution detail page,
- frontend cancellation action,
- artifact browser and download UI,
- date range filters,
- cursor pagination and indexing if needed,
- Agent-side running cancellation, retry, and requeue after explicit design,
- safe configuration summary DTO if needed,
- workload, Minecraft, and research naming use cases after those domains exist.
