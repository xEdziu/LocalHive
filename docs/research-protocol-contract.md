# Research Protocol Contract

M26 introduced a read-only communication protocol contract for future research work. M27 adds the first JSON-over-WebSocket research adapter for selected admin execution group operations. M28 adds a SOAP/XML research adapter for selected admin execution group read and control operations. M29 adds a static [Research Workload Catalog](research-workload-catalog.md) that can validate workload scenarios against the protocol contract. M30 adds the [Research Benchmark Recorder](research-benchmark-recorder.md) for storing benchmark runs, scenarios, measurements, and events. The contract describes how LocalHive Master exposes comparable admin operations without changing the core execution domain.

The protocol contract is a foundation only. It does not run benchmarks, execute workloads from the catalog, or change any worker-facing API.

## Purpose

The research contract answers four questions:

- which communication protocols are available or planned,
- which admin operations are part of the shared comparison surface,
- which payload formats and data transfer modes can be compared,
- whether a protocol, operation, transfer mode, and payload format combination is currently supported.

## Protocol Adapter Model

LocalHive treats REST, WebSocket, and SOAP as adapters over the same Master-side execution concepts. The current core remains:

- `WorkExecution`,
- `ExecutionGroup`,
- shard children,
- Agent-side merge executions,
- output artifacts,
- admin detail/activity read models.

M26 does not add protocol-specific execution behavior. Later milestones can register additional adapters against the same contract instead of duplicating execution logic.

## Protocols

| Protocol | Current status | Description |
| --- | --- | --- |
| `REST` | `AVAILABLE` | Existing HTTP admin API baseline. |
| `WEBSOCKET` | `AVAILABLE` | JSON-over-WebSocket research adapter for selected execution group operations. |
| `SOAP` | `AVAILABLE` | XML/SOAP research adapter for selected execution group operations. |

The M25 Server-Sent Events stream is an admin UI live update stream over REST. The M27 WebSocket adapter and M28 SOAP adapter are separate research protocol endpoints.

## Operations

M26 defines the initial shared operation enum:

| Operation | Mutating | Result type |
| --- | --- | --- |
| `CREATE_SINGLE_EXECUTION` | yes | `WORK_EXECUTION` |
| `CREATE_EXECUTION_GROUP` | yes | `EXECUTION_GROUP` |
| `GET_EXECUTION_STATUS` | no | `EXECUTION_STATUS` |
| `GET_GROUP_DETAIL` | no | `EXECUTION_GROUP_DETAIL` |
| `GET_GROUP_ACTIVITY` | no | `EXECUTION_GROUP_ACTIVITY` |
| `GET_GROUP_ARTIFACTS` | no | `EXECUTION_GROUP_ARTIFACTS` |
| `STREAM_GROUP_ACTIVITY` | no | `ACTIVITY_STREAM` |
| `STOP_STREAM_GROUP_ACTIVITY` | no | `STREAM_CONTROL` |
| `DOWNLOAD_ARTIFACT` | no | `ARTIFACT_BYTES` |
| `CANCEL_GROUP` | yes | `EXECUTION_GROUP` |
| `RECONCILE_GROUP` | yes | `EXECUTION_GROUP` |

Not every protocol supports every operation. REST remains the baseline for the broader admin API surface. WebSocket supports only the selected execution group operations listed below.

## Payload Formats

| Format | Description |
| --- | --- |
| `JSON` | Structured JSON payload. |
| `XML` | Structured XML or SOAP payload. |
| `BINARY` | Binary artifact or byte stream payload. |
| `MULTIPART` | Multipart HTTP payload used for file transfer. |

## Data Transfer Modes

| Mode | Description |
| --- | --- |
| `INLINE_JSON` | Request and response data are transferred as JSON payloads. |
| `INLINE_XML` | Request and response data are transferred as XML payloads. |
| `WORKSPACE_ARTIFACT` | Input data is provided through a workspace artifact. |
| `OUTPUT_ARTIFACT` | Result data is retrieved as an output artifact. |
| `STREAMED_EVENTS` | Updates are transferred as a server-to-client event stream. |

## Contract Endpoint

```http
GET /api/admin/research/protocol-contract
```

Behavior:

- ADMIN JWT required,
- worker API keys are rejected,
- response is read-only and safe,
- `generatedAt` reflects response generation time,
- protocol descriptors list status, payload formats, transfer modes, operations, and short descriptions.

Example shape:

```json
{
  "generatedAt": "2026-08-04T18:30:00",
  "protocols": [
    {
      "protocol": "REST",
      "status": "AVAILABLE",
      "description": "Existing HTTP/JSON admin API baseline.",
      "supportedPayloadFormats": ["BINARY", "JSON", "MULTIPART"],
      "supportedDataTransferModes": ["INLINE_JSON", "OUTPUT_ARTIFACT", "STREAMED_EVENTS", "WORKSPACE_ARTIFACT"],
      "supportedOperations": ["CREATE_EXECUTION_GROUP", "STREAM_GROUP_ACTIVITY"]
    }
  ],
  "operations": [],
  "dataTransferModes": [],
  "payloadFormats": []
}
```

## Validation Endpoint

```http
POST /api/admin/research/protocol-contract/validate
```

Request:

```json
{
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
  "reasonMessage": "Combination is supported."
}
```

Unsupported response:

```json
{
  "valid": false,
  "reasonCode": "OPERATION_NOT_SUPPORTED",
  "reasonMessage": "Operation DOWNLOAD_ARTIFACT is not supported by protocol SOAP."
}
```

Malformed JSON or invalid enum values return `400` through the existing error handling path. Valid requests for unsupported combinations return `200` with `valid = false`.

Stable reason codes:

- `SUPPORTED`,
- `UNKNOWN_PROTOCOL`,
- `UNKNOWN_OPERATION`,
- `UNKNOWN_DATA_TRANSFER_MODE`,
- `UNKNOWN_PAYLOAD_FORMAT`,
- `PROTOCOL_PLANNED`,
- `PROTOCOL_DISABLED`,
- `OPERATION_NOT_SUPPORTED`,
- `DATA_TRANSFER_MODE_NOT_SUPPORTED`,
- `PAYLOAD_FORMAT_NOT_SUPPORTED`,
- `COMBINATION_NOT_SUPPORTED`.

The `UNKNOWN_*` reason codes are part of the internal validation model. Public JSON enum binding can still reject unknown enum text with `400` before the validator runs.

M29 adds a separate workload validation endpoint:

```http
POST /api/admin/research/workload-catalog/validate
```

That endpoint first delegates to this protocol contract validator and then applies workload-specific rules such as execution shape and workspace artifact requirements.

M27 WebSocket combinations currently validate as supported for:

- `WEBSOCKET` + `GET_GROUP_DETAIL` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `GET_GROUP_ACTIVITY` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `GET_GROUP_ARTIFACTS` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `STREAM_GROUP_ACTIVITY` + `STREAMED_EVENTS` + `JSON`,
- `WEBSOCKET` + `STOP_STREAM_GROUP_ACTIVITY` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `CANCEL_GROUP` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `RECONCILE_GROUP` + `INLINE_JSON` + `JSON`.

For example, `WEBSOCKET` + `DOWNLOAD_ARTIFACT` + `OUTPUT_ARTIFACT` + `BINARY` validates as unsupported. Binary artifact transfer remains on the existing REST download endpoint.

M28 SOAP combinations currently validate as supported for:

- `SOAP` + `GET_GROUP_DETAIL` + `INLINE_XML` + `XML`,
- `SOAP` + `GET_GROUP_ACTIVITY` + `INLINE_XML` + `XML`,
- `SOAP` + `GET_GROUP_ARTIFACTS` + `INLINE_XML` + `XML`,
- `SOAP` + `CANCEL_GROUP` + `INLINE_XML` + `XML`,
- `SOAP` + `RECONCILE_GROUP` + `INLINE_XML` + `XML`.

For example, `SOAP` + `STREAM_GROUP_ACTIVITY` + `STREAMED_EVENTS` + `XML` and `SOAP` + `DOWNLOAD_ARTIFACT` + `OUTPUT_ARTIFACT` + `BINARY` validate as unsupported. SOAP M28 does not stream activity, transfer binary artifacts, use MTOM, upload workspace artifacts, or create execution groups.

## WebSocket Research Adapter

```text
ws://localhost:8080/api/admin/research/ws
```

Security:

- ADMIN JWT is required in the `Authorization: Bearer ...` header during the WebSocket handshake,
- worker API keys are rejected,
- unauthenticated handshakes are rejected,
- USER role is rejected,
- query-token authentication is not supported.

Client messages use a JSON envelope:

```json
{
  "requestId": "req-1",
  "operation": "GET_GROUP_DETAIL",
  "payload": {
    "executionGroupId": "00000000-0000-0000-0000-000000000000"
  }
}
```

Successful responses use:

```json
{
  "requestId": "req-1",
  "type": "RESPONSE",
  "operation": "GET_GROUP_DETAIL",
  "success": true,
  "data": {}
}
```

Errors use:

```json
{
  "requestId": "req-1",
  "type": "ERROR",
  "operation": "GET_GROUP_DETAIL",
  "success": false,
  "error": {
    "reasonCode": "GROUP_NOT_FOUND",
    "message": "Execution group not found."
  }
}
```

Stream events use:

```json
{
  "requestId": "stream-1",
  "type": "EVENT",
  "operation": "STREAM_GROUP_ACTIVITY",
  "event": "activity-snapshot",
  "success": true,
  "data": {}
}
```

Supported WebSocket operations:

| Operation | Payload | Response data |
| --- | --- | --- |
| `GET_GROUP_DETAIL` | `executionGroupId` | Existing safe group detail DTO. |
| `GET_GROUP_ACTIVITY` | `executionGroupId` | Existing safe group activity DTO. |
| `GET_GROUP_ARTIFACTS` | `executionGroupId` | Existing safe group artifact discovery DTO. |
| `STREAM_GROUP_ACTIVITY` | `executionGroupId`, optional stream settings | `EVENT` messages. |
| `STOP_STREAM_GROUP_ACTIVITY` | `streamRequestId` | `{ "streamRequestId": "...", "stopped": true/false }`. |
| `CANCEL_GROUP` | `executionGroupId`, optional `reason` | Existing safe group detail DTO after cancel. |
| `RECONCILE_GROUP` | `executionGroupId` | Existing safe group detail DTO after reconcile. |

Unsupported WebSocket operations return `ERROR` with `reasonCode = OPERATION_NOT_SUPPORTED`. M27 intentionally does not implement `CREATE_SINGLE_EXECUTION`, `CREATE_EXECUTION_GROUP`, `GET_EXECUTION_STATUS`, or `DOWNLOAD_ARTIFACT` over WebSocket.

WebSocket request validation:

- `requestId` is required, nonblank, and at most 100 characters,
- `operation` is required,
- `payload` must be a JSON object for supported operations,
- `executionGroupId` must be a UUID,
- `pollIntervalMs` defaults to `2000` and must be `500..10000`,
- `heartbeatIntervalMs` defaults to `10000`, must be `1000..60000`, and must be greater than or equal to `pollIntervalMs`,
- `maxEvents` is optional and must be `1..1000`,
- malformed JSON returns a safe error envelope when the connection is already established.

Stable WebSocket error reason codes:

- `MALFORMED_MESSAGE`,
- `INVALID_REQUEST_ID`,
- `UNKNOWN_OPERATION`,
- `INVALID_PAYLOAD`,
- `GROUP_NOT_FOUND`,
- `OPERATION_NOT_SUPPORTED`,
- `OPERATION_CONFLICT`,
- `UNAUTHORIZED`,
- `INTERNAL_ERROR`.

## WebSocket Activity Stream

`STREAM_GROUP_ACTIVITY` opens a lightweight derived stream for one WebSocket session and one `requestId`.

Initial event sequence:

1. `group-detail`
2. `activity-snapshot`

After the initial events, Master polls the existing safe group detail and activity read models. It sends a new `group-detail` or `activity-snapshot` event only when the safe DTO digest changes. The digest ignores volatile `generatedAt` fields so unchanged data does not produce duplicate snapshot events.

When no snapshot changed and the heartbeat interval elapsed, Master emits:

```json
{
  "executionGroupId": "00000000-0000-0000-0000-000000000000",
  "generatedAt": "2026-08-04T12:00:00",
  "status": "RUNNING"
}
```

Stream cleanup happens on:

- client disconnect,
- `STOP_STREAM_GROUP_ACTIVITY`,
- `maxEvents` reached,
- terminal group when `closeOnTerminal = true`,
- group-not-found or internal stream error.

## SOAP Research Adapter

```http
POST /api/admin/research/soap
```

Accepted request content types:

- `text/xml`,
- `application/soap+xml`.

Responses use a SOAP XML envelope with `application/soap+xml`.

SOAP namespace:

```text
https://localhive.dev/research/soap
```

Security:

- ADMIN JWT is required in `Authorization: Bearer ...`,
- worker API keys are rejected,
- unauthenticated requests are rejected,
- USER role is rejected,
- token-in-query authentication is not supported.

Supported SOAP operations:

| SOAP request | Contract operation | Response |
| --- | --- | --- |
| `GetGroupDetailRequest` | `GET_GROUP_DETAIL` | `GetGroupDetailResponse` |
| `GetGroupActivityRequest` | `GET_GROUP_ACTIVITY` | `GetGroupActivityResponse` |
| `GetGroupArtifactsRequest` | `GET_GROUP_ARTIFACTS` | `GetGroupArtifactsResponse` |
| `CancelGroupRequest` | `CANCEL_GROUP` | `CancelGroupResponse` |
| `ReconcileGroupRequest` | `RECONCILE_GROUP` | `ReconcileGroupResponse` |

Example request:

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:lh="https://localhive.dev/research/soap">
  <soapenv:Header/>
  <soapenv:Body>
    <lh:GetGroupDetailRequest>
      <lh:executionGroupId>00000000-0000-0000-0000-000000000000</lh:executionGroupId>
    </lh:GetGroupDetailRequest>
  </soapenv:Body>
</soapenv:Envelope>
```

Success responses use an operation-specific response element:

```xml
<lh:GetGroupDetailResponse>
  <lh:success>true</lh:success>
  <lh:data>
    <lh:executionGroupId>00000000-0000-0000-0000-000000000000</lh:executionGroupId>
    <lh:displayName>Example group</lh:displayName>
    <lh:status>RUNNING</lh:status>
  </lh:data>
</lh:GetGroupDetailResponse>
```

Application-level errors use a safe XML response:

```xml
<lh:GetGroupDetailResponse>
  <lh:success>false</lh:success>
  <lh:error>
    <lh:reasonCode>GROUP_NOT_FOUND</lh:reasonCode>
    <lh:message>Execution group not found.</lh:message>
  </lh:error>
</lh:GetGroupDetailResponse>
```

Stable SOAP error reason codes:

- `MALFORMED_MESSAGE`,
- `UNKNOWN_OPERATION`,
- `INVALID_PAYLOAD`,
- `GROUP_NOT_FOUND`,
- `OPERATION_NOT_SUPPORTED`,
- `OPERATION_CONFLICT`,
- `UNAUTHORIZED`,
- `INTERNAL_ERROR`.

Malformed XML or a malformed SOAP envelope returns HTTP `400` with a safe SOAP Fault. The fault does not include stack traces, exception class names, tokens, or local paths. Unsupported SOAP operation elements return an application-level error with `OPERATION_NOT_SUPPORTED` when the operation is known but outside M28, or `UNKNOWN_OPERATION` when the element is unknown.

SOAP data is a safe XML projection of existing admin group read models. It can include group identifiers, display names, statuses, merge mode, failure policy, shard counts, child counts, observability summary, lifecycle action metadata, activity events, artifact summaries, artifact ids, relative paths, filenames, content types, sizes, and timestamps.

SOAP responses do not expose raw execution configuration, raw merge plans, API keys, lease tokens, lease hashes, worker secrets, local filesystem paths, physical artifact paths, stack traces, or internal storage keys.

SOAP M28 intentionally does not implement:

- `CreateExecutionGroupRequest`,
- `StreamGroupActivityRequest`,
- `DownloadArtifactRequest`,
- `UploadWorkspaceArtifactRequest`,
- binary transfer,
- MTOM,
- workspace upload,
- artifact download.

## Security

Both endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Responses expose only safe research metadata: protocol names, statuses, operation names, payload format names, data transfer mode names, short descriptions, timestamps, validation booleans, and reason metadata.

Responses do not expose raw execution configuration, raw merge plans, API keys, lease tokens, lease hashes, worker secrets, local filesystem paths, physical artifact paths, stack traces, or internal storage keys.

The WebSocket endpoint is also under `/api/admin/**` and applies the same safe DTO boundary. It does not expose binary artifacts, raw configuration snapshots, workspace ZIPs, output artifact bytes, API keys, lease tokens, lease hashes, local filesystem paths, physical storage paths, stack traces, or internal storage keys.

## Current Limitations

The current research protocol foundation does not implement:

- SOAP streaming,
- SOAP binary or MTOM transfer,
- SOAP workspace upload,
- SOAP artifact download,
- SOAP execution group creation,
- WebSocket binary transfer,
- WebSocket workspace upload,
- WebSocket artifact download,
- WebSocket execution group creation,
- benchmark persistence,
- benchmark runner,
- protocol comparison runner,
- result export,
- fault injection,
- persistent protocol event log,
- changes to REST admin endpoint behavior,
- changes to worker protocol.

## Roadmap Relation

M27 makes `WEBSOCKET` available for selected safe execution group operations and activity streaming.

M28 makes `SOAP` available for selected safe execution group read/control operations over inline XML.

M29 uses `ResearchOperation`, `ResearchDataTransferMode`, and `ResearchPayloadFormat` to describe workload catalog entries and validate them against this contract.

M30 stores the protocol, operation, data transfer mode, and payload format enums as benchmark scenario dimensions.

M31 can use the protocol and workload validators to skip unsupported combinations before running comparisons.
