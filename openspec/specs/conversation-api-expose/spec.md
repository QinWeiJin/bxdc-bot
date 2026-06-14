# conversation-api-expose Specification

## Purpose
TBD - created by archiving change conversation-api-expose. Update Purpose after archive.
## Requirements
### Requirement: Publish Conversation as API
The system SHALL allow the conversation owner to publish a conversation as an API via `PUT /api/conversations/:id/publish`, providing an API description that will be injected as LLM context during API calls.

**Request**:
- Header: `X-User-Id` (conversation owner)
- Body: `{ "apiDescription": "该API提供数据分析服务..." }`

**Response** (`200 OK`): updated conversation object with `isPublished = true`, `apiDescription`, and a one-time `apiKey` (format: `c_` + 32-char UUID).

#### Scenario: Successful publish
- **WHEN** conversation owner sends `PUT /api/conversations/conv-abc/publish` with a valid `apiDescription`
- **AND** conversation is not yet published
- **THEN** `conversations.is_published` is set to `1` and `api_description` is saved
- **AND** an API Key is generated (format: `c_` + 32-char hex) and stored with its SHA-256 hash
- **AND** the response includes the raw API Key (only time it is shown)
- **AND** frontend renders `ApiDetailView` instead of `MessageList + MessageInput`

#### Scenario: Publish without api description
- **WHEN** user sends publish request with empty `apiDescription`
- **THEN** system returns `400 Bad Request` with `{ error: "API description is required" }`

#### Scenario: Double publish
- **WHEN** user tries to publish an already published conversation
- **THEN** system returns `422 Unprocessable Entity` with `{ error: "Conversation is already published" }`

#### Scenario: Non-owner cannot publish
- **WHEN** a user who is not the conversation owner attempts to publish
- **THEN** system returns `403 Forbidden`

### Requirement: Agent Chat API Endpoint
The system SHALL expose a synchronous HTTP API that accepts a conversation API Key and user input text, and returns the Agent's complete reply via `POST /api/agent-chat`.

**Request**:
- Body:
  - `apiKey` (string, required): the conversation's API Key (format: `c_` + 32 hex chars)
  - `instruction` (string, required): the user's input text
  - `callerId` (string, optional): caller identifier for log tracking

**Response** (`200 OK`):
```json
{
  "conversationId": "string",
  "reply": "Agent complete reply text",
  "toolCalls": 0,
  "durationMs": 0
}
```

#### Scenario: Successful API call with valid apiKey
- **WHEN** caller sends `POST /api/agent-chat` with `apiKey: "c_xxx..."` and `instruction: "帮我分析"`
- **AND** the apiKey maps to a published conversation with `api_description: "数据分析和报告生成服务"`
- **THEN** gateway resolves the conversation from the apiKey hash, injects the api_description as a system message into the history context, forwards to agent-core, collects the SSE stream response, writes an `api_call_logs` entry, and returns `200` with the complete `reply`

#### Scenario: Invalid or missing apiKey
- **WHEN** caller sends request without valid `apiKey`
- **THEN** system returns `401 Unauthorized`

#### Scenario: API key maps to unpublished conversation
- **WHEN** caller sends valid `apiKey` but the conversation has `is_published = false`
- **THEN** system returns `409 Conflict` with `{ error: "Conversation is not published" }`

#### Scenario: Agent-core unavailable
- **WHEN** agent-core service is down
- **THEN** system writes an `api_call_logs` entry with `status = 'error'` and returns `502 Bad Gateway`

#### Scenario: Agent processing timeout
- **WHEN** agent-core does not complete within configured timeout (default 120s)
- **THEN** system writes an `api_call_logs` entry with `status = 'timeout'` and returns `504 Gateway Timeout`

### Requirement: API Description Injection into LLM Context
The system SHALL insert the conversation's `api_description` as a `role: 'system'` message at the front of the `history` array passed to agent-core, so the LLM understands its API role throughout the conversation.

#### Scenario: API description injected as system message in history
- **WHEN** a published conversation has `api_description: "该API提供文本翻译服务"`
- **AND** caller sends `instruction: "翻译 hello world"`
- **THEN** the `history` array passed to agent-core begins with:
  `{ role: 'system', content: '你是一个已发布为 API 的助手，提供以下服务：\n该API提供文本翻译服务\n\n请严格按照上述描述提供服务，不要偏离描述的职责范围。如果用户请求超出上述范围，请礼貌告知用户该 API 不支持此功能。' }`
- **AND** the caller's original `instruction: "翻译 hello world"` is passed to agent-core without modification

### Requirement: API Call Logging
The system SHALL record every API call (success or failure) in the `api_call_logs` table.

#### Scenario: Successful call logged
- **WHEN** an API call completes with `reply: "分析结果..."` and took `3421ms`
- **THEN** system inserts a row into `api_call_logs` with `status = 'success'`, `instruction`, `reply`, `tool_call_count`, `duration_ms`

#### Scenario: Timeout call logged
- **WHEN** an API call times out after 120s
- **THEN** system inserts a row into `api_call_logs` with `status = 'timeout'`, `instruction` (but no reply), `error_message = 'Agent processing timeout'`

#### Scenario: Caller ID tracked
- **WHEN** caller provides `callerId: "external-app-001"`
- **THEN** the `api_call_logs` row includes `caller_id = "external-app-001"`

### Requirement: Call Logs Query
The system SHALL provide a paginated call logs query endpoint `GET /api/conversations/:id/call-logs` for the conversation owner.

#### Scenario: Query call logs
- **WHEN** conversation owner sends `GET /api/conversations/conv-abc/call-logs?page=1&size=20`
- **THEN** system returns a paginated list of API call records ordered by `created_at DESC`, with `total` and `hasMore` fields

### Requirement: Concurrent API Call Isolation
The system SHALL ensure that multiple concurrent API calls to the same published conversation do not interfere with each other's responses.

#### Scenario: Two callers invoke simultaneously
- **WHEN** caller A and caller B both send requests to the same conversation at nearly the same time
- **THEN** each call generates an independent `sessionId`
- **AND** each call receives a reply based solely on its own `instruction` + `api_description` + history snapshot
- **AND** both calls are logged as separate rows in `api_call_logs`

### Requirement: Published Conversation UI — API Detail View
The system SHALL render the `ApiDetailView` component when the user opens a published conversation, displaying API information, API Key management, and call history instead of the chat interface.

#### Scenario: Open published conversation
- **WHEN** user clicks a published conversation in the sidebar
- **THEN** `ChatView` renders `ApiDetailView` showing: API description text, API Key (masked, with copy/regenerate buttons), API endpoint (`POST /api/agent-chat`), request body format, curl example, and call logs table

#### Scenario: Open unpublished conversation
- **WHEN** user clicks an unpublished conversation
- **THEN** `ChatView` renders the existing chat interface (`MessageList` + `MessageInput`)

### Requirement: API Key Generation on Publish
The system SHALL automatically generate a unique API Key for each published conversation and return it to the user exactly once.

#### Scenario: API Key generated on publish
- **WHEN** a conversation is published
- **THEN** a new API Key is generated in format `c_` + 32-character hex (UUID without dashes)
- **AND** the raw key is stored in `conversations.api_key`
- **AND** the SHA-256 hash is stored in `conversations.api_key_hash` with a unique index
- **AND** the publish response includes the raw `apiKey` (the only time it is returned in full)

### Requirement: API Key Authentication
The system SHALL authenticate API calls by matching the provided `apiKey` against stored hashes.

#### Scenario: Valid apiKey resolves to conversation
- **WHEN** caller sends `POST /api/agent-chat` with `apiKey: "c_xxx..."`
- **THEN** gateway computes SHA-256 of the key and queries `conversations` by `api_key_hash`
- **AND** if a matching conversation is found and `is_published = true`, the call proceeds

### Requirement: API Key Regeneration
The system SHALL allow the conversation owner to regenerate the API Key via `PUT /api/conversations/:id/regenerate-api-key`.

#### Scenario: Regenerate API Key
- **WHEN** conversation owner sends `PUT /api/conversations/:id/regenerate-api-key`
- **THEN** a new API Key is generated
- **AND** both `api_key` and `api_key_hash` are updated
- **AND** the old Key immediately becomes invalid
- **AND** the response includes the new raw `apiKey` (only time it is shown)

### Requirement: API Key Display in Detail View
The system SHALL display the API Key in masked form on the ApiDetailView, with the ability to copy the full key.

#### Scenario: API Key displayed masked
- **WHEN** user views the API detail page
- **THEN** the API Key is displayed as `c_****...****<last 4 chars>` with a copy button
- **AND** clicking the copy button copies the full raw key to clipboard

### Requirement: Publish Entry Point in Sidebar
The system SHALL provide a "发布为API" button for each unpublished conversation in the sidebar.

#### Scenario: Unpublished conversation shows publish button
- **WHEN** conversation `is_published = false`
- **THEN** sidebar shows a "发布为API" icon button for that conversation item

#### Scenario: Published conversation shows API badge
- **WHEN** conversation `is_published = true`
- **THEN** sidebar shows a small "API" badge next to the conversation name instead of the publish button

### Requirement: Publish Modal
The system SHALL show a modal dialog when the user clicks "发布为API", allowing the user to enter an API description before confirming the publish.

#### Scenario: Open publish modal
- **WHEN** user clicks "发布为API" button on an unpublished conversation
- **THEN** `PublishApiModal` dialog appears with: API description textarea (required, max 2000 chars), read-only API URL display, and info alert about post-publish behavior

#### Scenario: Confirm publish
- **WHEN** user fills in API description and clicks "确认发布"
- **THEN** system calls `PUT /api/conversations/:id/publish`, closes the modal, and the current page switches to `ApiDetailView`

