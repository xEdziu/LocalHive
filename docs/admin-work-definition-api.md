# Admin Work Definition API

M9 adds read-only production admin endpoints for browsing Work Definitions and their immutable versions.

Dev-smoke endpoints can create local test executions. The [Admin Execution API](admin-execution-api.md) can inspect execution history, the [Admin Worker API](admin-worker-api.md) can inspect one worker and its recent execution activity, and the M10 [Admin Create Execution API](admin-create-execution-api.md) can create a one-off execution from an approved Work Definition Version.

The Admin Work Definition API provides the read-only browsing surface used before execution creation. It does not create, update, delete, enable, disable, schedule, or execute work.

## List Work Definitions

```http
GET /api/admin/work-definitions
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Query parameters:

| Parameter | Required | Description |
| --- | --- | --- |
| `limit` | no | Page size. Defaults to `50`; maximum is `200`. |
| `offset` | no | Zero-based offset. Defaults to `0`. |
| `type` | no | Exact `WorkType` enum value, for example `TASK` or `WORKLOAD`. |
| `logicalId` | no | Exact logical identifier match. |

Behavior:

- response wrapper contains `items`, `limit`, `offset`, and `totalCount`,
- results are sorted by `logicalId` ascending,
- pagination is offset-based,
- `offset` applies after filters and sorting,
- `totalCount` respects active filters,
- empty pages return `items: []`,
- invalid `limit` returns `400`,
- invalid `offset` returns `400`,
- invalid `type` returns `400`,
- an unknown `logicalId` returns `items: []` and `totalCount: 0`.

Response shape:

```json
{
  "items": [
    {
      "definitionId": "00000000-0000-0000-0000-000000000000",
      "logicalId": "localhive.docker.workload",
      "type": "TASK",
      "sourceType": "LOCAL",
      "name": "Docker Workload",
      "description": "Run an allowed local Docker workload.",
      "latestVersion": 1,
      "versionCount": 1,
      "latestVersionId": "11111111-1111-1111-1111-111111111111",
      "latestExecutorId": "localhive.docker.workload",
      "latestExecutorContractVersion": 1,
      "latestApprovalStatus": "APPROVED",
      "createdAt": "2026-07-20T10:00:00"
    }
  ],
  "limit": 50,
  "offset": 0,
  "totalCount": 1
}
```

`limit=200` is the maximum page size, not a full definition history limit. Use `offset` to access more rows when the result set is larger than one page.

Example:

```http
GET /api/admin/work-definitions?type=TASK&limit=20&offset=0
Accept: application/json
Authorization: Bearer {{auth_token}}
```

Exact logical identifier filter:

```http
GET /api/admin/work-definitions?logicalId=localhive.docker.workload&limit=20&offset=0
Accept: application/json
Authorization: Bearer {{auth_token}}
```

## Work Definition Detail

```http
GET /api/admin/work-definitions/{definitionId}
```

Security:

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Behavior:

- an existing definition returns `200`,
- a missing definition returns `404`,
- an invalid UUID returns the current `400`-style validation response,
- the response includes definition metadata and versions,
- versions are newest-first,
- the latest version is marked.

Response shape:

```json
{
  "definitionId": "00000000-0000-0000-0000-000000000000",
  "logicalId": "localhive.docker.workload",
  "type": "TASK",
  "sourceType": "LOCAL",
  "name": "Docker Workload",
  "description": "Run an allowed local Docker workload.",
  "createdAt": "2026-07-20T10:00:00",
  "versions": [
    {
      "versionId": "11111111-1111-1111-1111-111111111111",
      "version": 1,
      "latest": true,
      "name": "Docker Workload",
      "description": "Run an allowed local Docker workload.",
      "executorId": "localhive.docker.workload",
      "executorContractVersion": 1,
      "approvalStatus": "APPROVED",
      "createdAt": "2026-07-20T10:00:00"
    }
  ]
}
```

## Latest Version Behavior

The latest version is calculated as the highest numeric domain `versionNumber` for one Work Definition. The list endpoint exposes that value as `latestVersion`; the detail endpoint exposes each version number as `version` and marks the latest version with `latest: true`.

M9 does not add an active/latest database flag or migration. If a definition exists without versions, latest summary fields can be `null`, `versionCount` can be `0`, and the detail response can return an empty `versions` list.

## Safe Metadata / Non-Exposed Fields

M9 intentionally exposes only safe metadata needed for browsing and selecting definitions. The Admin Work Definition API does not expose:

- raw `executorConfiguration`,
- `requestedConfigurationSnapshot`,
- `resolvedConfigurationSnapshot`,
- API keys,
- API key hashes,
- password hashes,
- lease tokens,
- lease token hashes,
- `leaseExpiresAt`,
- `storagePath`,
- `dataRoot`,
- physical absolute storage paths,
- stack traces,
- internal exception details.

Execution creation is implemented separately by the M10 [Admin Create Execution API](admin-create-execution-api.md).

## Pagination

The list endpoint uses the same offset-based pagination model as the Admin Execution API.

- `limit` controls page size.
- `offset` selects the zero-based result offset after filters and `logicalId ASC` sorting.
- `totalCount` respects active filters.

Future cursor pagination can be considered if definition history grows enough to require it.

## Filtering

The `type` filter is an exact enum filter. Current values are `TASK` and `WORKLOAD`.

The `logicalId` filter is an exact match. It is useful when an admin client knows the stable logical identifier and needs to find the definition id or latest version metadata.

Filters affect both `items` and `totalCount`.

## Relation To Execution Creation

M9 is read-only. It lets a frontend or admin tool list definitions, inspect their versions, and select a definition/version for execution creation.

M10 adds a separate [Admin Create Execution API](admin-create-execution-api.md). This Work Definition API still does not create executions and does not create, update, delete, enable, disable, schedule, or execute Work Definitions.

## Current Limitations

- read-only only,
- no create, update, or delete Work Definition API,
- no execution creation through this Work Definition API,
- no YAML import,
- no safe config schema DTO yet,
- no frontend UI yet,
- no scheduler,
- no multi-worker execution,
- no active/latest database flag,
- no enable or disable definition flow.

## Future Extensions

Future work may add:

- Work Definition create, update, or import after explicit design,
- YAML or template import,
- safe parameter schema DTO,
- frontend definition picker,
- active or disabled definition lifecycle,
- version management UI,
- scheduler integration,
- multi-worker or distributed execution definitions after those domains are designed.
