## ADDED Requirements

### Requirement: Save Messages to Conversation
The system SHALL accept a batch of messages to be persisted under a specific conversation via `POST /api/conversations/:id/messages`.

**Request**:
- Header: `X-User-Id` (required)
- Body:
  - `messages` (array, required): list of message objects, each containing:
    - `role` (string, required): one of `user`, `assistant`, `tool`, `system`
    - `content` (string, required): message body text
    - `skill_calls` (object, optional): JSON object describing tool calls made during this assistant turn
    - `skill_outputs` (object, optional): JSON object containing tool execution results

**Response**: `201 Created` with `{ ok: true, count: <number of messages saved> }`.

**Behavior**: Each message is assigned a UUID as `message_id`. Messages are inserted in order. The conversation's `updated_at` is refreshed to `NOW()`.

#### Scenario: Save a complete round of messages
- **WHEN** system sends 3 messages (1 user, 1 assistant with tool calls, 1 tool output)
- **THEN** all 3 messages are inserted into `conversation_messages` with auto-generated `message_id` UUIDs; the conversation's `updated_at` is refreshed; response includes `count: 3`

#### Scenario: Save messages to non-existent conversation
- **WHEN** system sends messages to a conversation ID that does not exist
- **THEN** system returns `404 Not Found`

#### Scenario: Invalid message role
- **WHEN** a message in the batch has `role: "unknown"`
- **THEN** system returns `400 Bad Request` with an error message indicating the invalid role

#### Scenario: Empty message batch
- **WHEN** `messages` array is empty
- **THEN** system returns `200 OK` with `{ ok: true, count: 0 }` (no-op)

---

### Requirement: Message Role Constraint
The system SHALL only accept messages with roles `user`, `assistant`, `tool`, or `system`.

#### Scenario: Reject invalid role
- **WHEN** a message has `role: "bot"`
- **THEN** system returns `400 Bad Request`

---

### Requirement: Tool Call Data Preservation
The system SHALL store `skill_calls` and `skill_outputs` as JSON columns without validation, preserving whatever structure the frontend provides.

#### Scenario: Save message with nested tool call data
- **WHEN** an assistant message includes `skill_calls: [{ name: "extended_query_api", arguments: {...}, status: "completed" }]`
- **THEN** system stores the JSON as-is in the `skill_calls` column

#### Scenario: Save message without tool data
- **WHEN** a user message has no `skill_calls` or `skill_outputs`
- **THEN** both columns are stored as `NULL`
