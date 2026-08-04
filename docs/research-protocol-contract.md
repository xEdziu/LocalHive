# Research Protocol Contract

M26 introduced a read-only communication protocol contract for future research work. M27 adds the first JSON-over-WebSocket research adapter for selected admin execution group operations. The contract describes how LocalHive Master exposes comparable admin operations without changing the core execution domain.

The contract is a foundation only. It does not run benchmarks, persist metrics, create workloads, add a SOAP endpoint, or change any worker-facing API.

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
| `SOAP` | `PLANNED` | Future XML/SOAP enterprise-style research adapter. |

The M25 Server-Sent Events stream is an admin UI live update stream over REST. The M27 WebSocket adapter is a separate research protocol endpoint.

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
  "reasonCode": "PROTOCOL_PLANNED",
  "reasonMessage": "Protocol SOAP is planned but not available yet."
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

M27 WebSocket combinations currently validate as supported for:

- `WEBSOCKET` + `GET_GROUP_DETAIL` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `GET_GROUP_ACTIVITY` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `GET_GROUP_ARTIFACTS` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `STREAM_GROUP_ACTIVITY` + `STREAMED_EVENTS` + `JSON`,
- `WEBSOCKET` + `STOP_STREAM_GROUP_ACTIVITY` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `CANCEL_GROUP` + `INLINE_JSON` + `JSON`,
- `WEBSOCKET` + `RECONCILE_GROUP` + `INLINE_JSON` + `JSON`.

For example, `WEBSOCKET` + `DOWNLOAD_ARTIFACT` + `OUTPUT_ARTIFACT` + `BINARY` validates as unsupported. Binary artifact transfer remains on the existing REST download endpoint.

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

- SOAP endpoint or adapter,
- WebSocket binary transfer,
- WebSocket workspace upload,
- WebSocket artifact download,
- WebSocket execution group creation,
- benchmark persistence,
- benchmark runner,
- workload catalog,
- protocol comparison runner,
- result export,
- fault injection,
- persistent protocol event log,
- changes to REST admin endpoint behavior,
- changes to worker protocol.

## Roadmap Relation

M27 makes `WEBSOCKET` available for selected safe execution group operations and activity streaming.

M28 can make `SOAP` available by registering a SOAP adapter against XML/SOAP operations.

M29 can use `ResearchOperation`, `ResearchDataTransferMode`, and `ResearchPayloadFormat` to describe workload catalog entries.

M30 can use the protocol, operation, data transfer mode, and payload format enums as benchmark dimensions.

M31 can use the validator to skip unsupported protocol combinations before running comparisons.
