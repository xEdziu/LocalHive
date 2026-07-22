# Simple Optimization Workload

M15 documents the first simple optimization workload recipe using the existing Docker workload flow.

This is not sharding, a new executor, a new `WorkType`, or new Agent behavior. The optimization code is packaged as a workspace artifact, mounted read-only into the existing Docker workload container, and the script writes results to `/output` for upload as execution output artifacts.

## Purpose

The M15 flow proves the base path:

```text
workspace package -> Docker workload -> output artifacts
```

The workload runs as a normal `localhive.docker.workload` execution:

- Master stores the uploaded workspace ZIP as a `WORKSPACE_PACKAGE` artifact.
- Master creates a Docker workload execution that references that workspace artifact.
- Agent downloads and safely unpacks the workspace after claiming the execution.
- Docker mounts the workspace read-only at `/workspace`.
- Docker runs `sh /workspace/optimize.sh`.
- The script writes results to `/output`.
- Agent uploads output artifacts back to Master.

M15 does not add shard planning, parent or child executions, merge/reduce, retry, requeue, a dedicated optimization executor, or any Docker runtime behavior change.

## Workspace Package

Expected ZIP structure:

```text
workspace.zip
└── optimize.sh
```

The local ZIP is a smoke input and should not be committed.

`optimize.sh` is expected to:

- run through `sh /workspace/optimize.sh`,
- write `/output/result.json`,
- write `/output/summary.txt`.

## Optimization Function

The script should evaluate:

```text
score = 10000 - (x - 37)^2 - (y - 82)^2
x = 0..100
y = 0..100
```

Expected best result:

```json
{"bestX":37,"bestY":82,"score":10000}
```

## Requirements

Before running the smoke flow:

- Master is running.
- Agent is running.
- Worker is approved.
- Worker is online and available when using `AUTO`.
- Worker has current capabilities when using M13 `AUTO` or `PREFER`.
- Agent Docker policy allows `alpine:3.20`.
- The Docker Work Definition Version for `localhive.docker.workload` exists and is approved.
- Workspace artifact upload endpoint is available.
- Output artifact endpoints are available.

## Docker Workload Configuration

The execution uses the existing Docker workload executor:

```json
{
  "image": "alpine:3.20",
  "command": [
    "sh",
    "/workspace/optimize.sh"
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
    "artifactId": "{{m15_workspace_artifact_uuid}}",
    "mountPath": "/workspace",
    "readOnly": true
  }
}
```

Workspace rules remain the existing workspace artifact rules:

- `mountPath` must be `/workspace`,
- `readOnly` must be `true`,
- the host path is controlled by the Agent, not by user configuration,
- the workspace mount is read-only inside the container.

## Flow

1. Upload `workspace.zip` as `WORKSPACE_PACKAGE`.
2. Find the Docker Work Definition Version.
3. Optionally run selection diagnostics.
4. Create the Docker workload execution.
5. Agent claims and runs the container.
6. Container runs `sh /workspace/optimize.sh`.
7. Script writes `/output/result.json` and `/output/summary.txt`.
8. Agent uploads output artifacts.
9. Admin downloads the result artifact.
10. Result matches the expected best point.

## Upload Workspace

```http
POST http://localhost:8080/api/dev/artifacts/workspace-package
Authorization: Bearer {{auth_token}}
Content-Type: multipart/form-data; boundary=LocalHiveBoundary

--LocalHiveBoundary
Content-Disposition: form-data; name="file"; filename="workspace.zip"
Content-Type: application/zip

< E:/LocalHiveSmoke/m15/workspace.zip
--LocalHiveBoundary--
```

Capture the artifact id:

```js
client.global.set("m15_workspace_artifact_uuid", response.body.artifactId);
```

## Find Docker Definition

```http
GET http://localhost:8080/api/admin/work-definitions?logicalId=localhive.docker.workload&limit=20&offset=0
Authorization: Bearer {{auth_token}}
Accept: application/json
```

Capture the latest approved Docker definition version id:

```js
client.global.set("docker_definition_version_uuid", response.body.items[0].latestVersionId);
```

## Selection Diagnostics

Diagnostics is optional but useful before creating an `AUTO` execution.

```http
POST http://localhost:8080/api/admin/executions/selection-diagnostics
Authorization: Bearer {{auth_token}}
Content-Type: application/json
Accept: application/json

{
  "workDefinitionVersionId": "{{docker_definition_version_uuid}}",
  "assignmentMode": "AUTO",
  "displayName": "M15 Simple Optimization Diagnostics",
  "configuration": {
    "image": "alpine:3.20",
    "command": [
      "sh",
      "/workspace/optimize.sh"
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
      "artifactId": "{{m15_workspace_artifact_uuid}}",
      "mountPath": "/workspace",
      "readOnly": true
    }
  }
}
```

Expected diagnostics:

- request is structurally valid,
- at least one worker is eligible for `AUTO`,
- selected worker has Docker capability for `alpine:3.20`,
- selected worker fits requested RAM and CPU.

## Create Execution

```http
POST http://localhost:8080/api/admin/executions
Authorization: Bearer {{auth_token}}
Content-Type: application/json
Accept: application/json

{
  "workDefinitionVersionId": "{{docker_definition_version_uuid}}",
  "assignmentMode": "AUTO",
  "displayName": "M15 Simple Optimization",
  "configuration": {
    "image": "alpine:3.20",
    "command": [
      "sh",
      "/workspace/optimize.sh"
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
      "artifactId": "{{m15_workspace_artifact_uuid}}",
      "mountPath": "/workspace",
      "readOnly": true
    }
  }
}
```

Capture the execution id:

```js
client.global.set("m15_execution_uuid", response.body.executionId);
```

## Check Execution Detail

```http
GET http://localhost:8080/api/admin/executions/{{m15_execution_uuid}}
Authorization: Bearer {{auth_token}}
Accept: application/json
```

Expected after Agent completes the execution:

- `status` eventually becomes `SUCCEEDED`,
- `displayName` is `M15 Simple Optimization`,
- `artifacts.outputArtifactCount >= 2`.

## List Output Artifacts

```http
GET http://localhost:8080/api/admin/executions/{{m15_execution_uuid}}/artifacts
Authorization: Bearer {{auth_token}}
Accept: application/json
```

Expected artifacts:

- `result.json`,
- `summary.txt`.

Capture `result.json`:

```js
const result = response.body.find((artifact) => artifact.originalFilename === "result.json");
client.global.set("m15_result_artifact_uuid", result.artifactId);
```

## Download Result

```http
GET http://localhost:8080/api/admin/artifacts/{{m15_result_artifact_uuid}}/download
Authorization: Bearer {{auth_token}}
Accept: application/octet-stream
```

Expected response body:

```json
{"bestX":37,"bestY":82,"score":10000}
```

`summary.txt` should be a human-readable summary of the same best point.

## Current Limitations

- single worker only,
- no sharding,
- no parent or child executions,
- no merge/reduce,
- no retry or requeue,
- no dedicated optimization executor,
- no Python, C++, or Java runner images yet,
- no Docker image policy expansion beyond existing allowed images,
- no live Docker health probing,
- no frontend UI,
- no GPU support.

## Relation To Sharding

M15 is preparation for future sharding, not an implementation of sharding.

It proves that LocalHive can move a small code package into an Agent-controlled Docker execution and move results back through output artifacts. Sharding design should come later, after the S5 combined smoke backlog validates the current worker selection, diagnostics, cancellation, workspace, and output artifact flows.
