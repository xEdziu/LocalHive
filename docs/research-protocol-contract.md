# Research Protocol Contract

M26 introduces a read-only communication protocol contract for future research work. It describes how LocalHive Master currently exposes admin operations and how later protocol adapters can be compared without changing the core execution domain.

The contract is a foundation only. It does not run benchmarks, persist metrics, create workloads, add WebSocket or SOAP endpoints, or change any worker-facing API.

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

| Protocol | M26 status | Description |
| --- | --- | --- |
| `REST` | `AVAILABLE` | Existing HTTP admin API baseline. |
| `WEBSOCKET` | `PLANNED` | Future bidirectional real-time research adapter. |
| `SOAP` | `PLANNED` | Future XML/SOAP enterprise-style research adapter. |

The M25 Server-Sent Events stream is an admin UI live update stream. It is not the M27 WebSocket research adapter.

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
| `STREAM_GROUP_ACTIVITY` | no | `SSE_STREAM` |
| `DOWNLOAD_ARTIFACT` | no | `ARTIFACT_BYTES` |
| `CANCEL_GROUP` | yes | `EXECUTION_GROUP` |
| `RECONCILE_GROUP` | yes | `EXECUTION_GROUP` |

Not every protocol supports every operation. In M26 only REST exposes supported operations.

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
  "reasonMessage": "Protocol WEBSOCKET is planned but not available yet."
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

## Security

Both endpoints are under `/api/admin/**`.

- ADMIN JWT required.
- Worker API key is not accepted.
- Unauthenticated requests are rejected.
- USER role is rejected.

Responses expose only safe research metadata: protocol names, statuses, operation names, payload format names, data transfer mode names, short descriptions, timestamps, validation booleans, and reason metadata.

Responses do not expose raw execution configuration, raw merge plans, API keys, lease tokens, lease hashes, worker secrets, local filesystem paths, physical artifact paths, stack traces, or internal storage keys.

## Current Limitations

M26 does not implement:

- WebSocket endpoint or adapter,
- SOAP endpoint or adapter,
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

M27 can make `WEBSOCKET` available by registering a WebSocket adapter against the same operations and transfer modes.

M28 can make `SOAP` available by registering a SOAP adapter against XML/SOAP operations.

M29 can use `ResearchOperation`, `ResearchDataTransferMode`, and `ResearchPayloadFormat` to describe workload catalog entries.

M30 can use the protocol, operation, data transfer mode, and payload format enums as benchmark dimensions.

M31 can use the validator to skip unsupported protocol combinations before running comparisons.
