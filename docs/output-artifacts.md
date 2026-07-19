# Output Artifacts

Output artifacts are small files produced by a workload execution and uploaded by a Worker/Agent to the Master.

Examples:

- `result.txt`
- `logs/summary.txt`
- `reports/output.json`
- `benchmark/result.csv`

## Current Flow

1. Docker workload writes files under the Agent-controlled `/output` directory.
2. Agent scans `/output` after the container exits.
3. Agent uploads regular files to the Master before reporting terminal execution status.
4. Master stores each file as an `EXECUTION_OUTPUT` artifact.
5. Master links each artifact to the `WorkExecution`.
6. Admin can list output artifacts for an execution.
7. Admin can download each output artifact.

Normal Agent flow uploads output artifacts while the execution is `RUNNING`. Uploads for terminal executions are rejected.

## Worker Upload Endpoint

```http
POST /api/workers/{workerId}/executions/{executionId}/artifacts/output
```

Headers:

```text
X-API-KEY
X-EXECUTION-LEASE
```

Request:

- `multipart/form-data`
- required field: `file`
- optional field: `relativePath`

Allowed execution statuses:

- `CLAIMED`
- `RUNNING`

The upload is worker-scoped and execution-scoped. The worker API key authenticates the worker, and the execution lease proves that the worker currently owns the claimed execution.

## Admin List Endpoint

```http
GET /api/admin/executions/{executionId}/artifacts
```

This endpoint requires an Admin JWT. It returns output artifacts for the execution and does not expose the internal `storagePath`.

Example response:

```json
[
  {
    "artifactId": "00000000-0000-0000-0000-000000000000",
    "kind": "EXECUTION_OUTPUT",
    "executionId": "11111111-1111-1111-1111-111111111111",
    "uploadedByWorkerId": "22222222-2222-2222-2222-222222222222",
    "relativePath": "results/output.txt",
    "originalFilename": "output.txt",
    "contentType": "application/octet-stream",
    "sizeBytes": 32,
    "sha256": "hex",
    "createdAt": "2026-07-19T14:58:23.130716"
  }
]
```

## Admin Download Endpoint

```http
GET /api/admin/artifacts/{artifactId}/download
```

This endpoint requires an Admin JWT. It downloads only `EXECUTION_OUTPUT` artifacts. `WORKSPACE_PACKAGE` artifacts are not downloadable through this endpoint.

The response is a binary stream. Master sets a safe attachment filename and does not expose the internal `storagePath`.

## Storage Behavior

Master stores files using Master-generated physical paths:

```text
.localhive-master/artifacts/<artifactId>/artifact
```

Important storage rules:

- `relativePath` is metadata only.
- `relativePath` is not used as a physical storage path.
- original filename is not used as a physical storage path.
- `storagePath` stays internal.
- SHA-256 is calculated while the file is stored.

## Relative Path Policy

`relativePath` is optional. When it is omitted, Master falls back to the sanitized original filename.

Policy:

- maximum length: 1024 characters
- must be relative
- absolute paths are rejected
- parent traversal with `..` is rejected
- Windows drive paths are rejected
- backslash traversal is normalized to `/` and validated
- null bytes are rejected
- path separators are normalized to `/`
- value is metadata only

## Limits

- maximum output artifact size: 50 MiB

Large multi-GB artifacts are not supported.

## Security Notes

- Worker API key is required.
- Execution lease is required.
- Upload is validated against the assigned worker and execution.
- Terminal execution upload is rejected.
- Upload is streamed.
- `storagePath` is not exposed through API responses.
- API key and lease token are not returned.
- User-controlled paths are metadata only and are not physical storage paths.

## Current Limitations

- no Agent cleanup or retention policy yet
- no large multi-GB artifacts
- no output artifact UI yet
- no preview/viewer endpoint
- no artifact deletion or retention policy
- no distributed or sharded output aggregation yet

## Future Extensions

Future work may add:

- Master UI for execution output artifacts
- output artifact retention policy
- output artifact cleanup
- large artifact storage policy
- previews for text, JSON, and log artifacts
- artifact grouping in execution details
- research telemetry for upload duration and size if needed later

## Cross-Platform Note

The default storage root is relative for local/dev use. A future setup flow may configure a Windows or Linux data root. Physical paths should remain Master-generated and OS-neutral.
