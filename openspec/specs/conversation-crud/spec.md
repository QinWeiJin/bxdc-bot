## ADDED Requirements

### Requirement: Create Conversation
The system SHALL allow an authenticated user to create a new conversation via `POST /api/conversations`.

**Request**:
- Header: `X-User-Id` (required, the authenticated user's ID)
- Body:
  - `name` (string, optional): conversation display name; defaults to empty string
  - `enabled_skills` (number[], optional): list of enabled Skill IDs; defaults to empty array
  - `enabled_files` (number[], optional): list of enabled file IDs; defaults to empty array

**Response**: `201 Created` with JSON body containing `id`, `conversation_id` (UUID), `name`, `enabled_skills`, `enabled_files`, `status`, `created_at`, `updated_at`.

#### Scenario: Create conversation with full fields
- **WHEN** user sends `POST /api/conversations` with `name: "项目A分析", enabled_skills: [1, 3, 5], enabled_files: [10, 12]`
- **THEN** system creates a new conversation record with `status = active`, a generated UUID as `conversation_id`, `enabled_files = [10, 12]`, and returns the full conversation object

#### Scenario: Create conversation with minimal fields
- **WHEN** user sends `POST /api/conversations` with empty body
- **THEN** system creates a new conversation with `name = ""`, `enabled_skills = []`, `enabled_files = []`, and returns the full conversation object

#### Scenario: Missing user authentication
- **WHEN** request is sent without `X-User-Id` header
- **THEN** system returns `401 Unauthorized`

---

### Requirement: List User Conversations
The system SHALL return all active conversations belonging to the authenticated user via `GET /api/conversations`.

**Response**: `200 OK` with JSON body containing `conversations` array. Each item includes `id`, `conversation_id`, `name`, `enabled_skills`, `enabled_files`, `created_at`, `updated_at`. Results are ordered by `updated_at DESC` (most recently updated first).

#### Scenario: User has conversations
- **WHEN** user has 3 conversations and sends `GET /api/conversations`
- **THEN** system returns array of 3 conversation objects ordered by most recently updated first, each including `enabled_files`

#### Scenario: User has no conversations
- **WHEN** user has no conversations in the database
- **THEN** system returns `{ conversations: [] }` with HTTP 200

---

### Requirement: Get Conversation Detail with Messages
The system SHALL return a single conversation's details and its paginated message history via `GET /api/conversations/:id`.

**Query Parameters**:
- `cursor` (string, optional): ISO 8601 timestamp. Messages with `created_at` < cursor are returned. Omit for latest messages.
- `limit` (integer, optional): number of messages per page. Default 50, max 100.

**Response**: `200 OK` with:
- `conversation`: conversation object including `enabled_files`
- `messages`: array of message objects (`message_id`, `role`, `content`, `skill_calls`, `skill_outputs`, `created_at`), ordered by `created_at DESC`
- `hasMore`: boolean indicating if more messages exist before the oldest in this page

#### Scenario: Load latest messages for a conversation
- **WHEN** user sends `GET /api/conversations/conv-abc` without cursor
- **THEN** system returns the conversation details (including `enabled_files`) and the 50 most recent messages

#### Scenario: Load earlier messages with cursor
- **WHEN** user sends `GET /api/conversations/conv-abc?cursor=2026-06-01T12:00:00&limit=20`
- **THEN** system returns 20 messages with `created_at` before `2026-06-01T12:00:00`

#### Scenario: Conversation not found
- **WHEN** user requests a non-existent conversation ID
- **THEN** system returns `404 Not Found`

#### Scenario: Conversation belongs to another user
- **WHEN** user requests a conversation owned by a different user
- **THEN** system returns `404 Not Found` (not `403`, to avoid leaking existence)

---

### Requirement: Update Conversation
The system SHALL allow updating a conversation's `name`, `enabled_skills`, and/or `enabled_files` via `PUT /api/conversations/:id`.

**Request Body** (partial update, all fields optional):
- `name` (string, optional): new display name
- `enabled_skills` (number[], optional): new Skill ID list
- `enabled_files` (number[], optional): new file ID list

**Response**: `200 OK` with the updated conversation object including `enabled_files`.

#### Scenario: Rename a conversation
- **WHEN** user sends `PUT /api/conversations/conv-abc` with `name: "新名称"`
- **THEN** system updates the conversation name and returns the updated object; `enabled_skills` and `enabled_files` remain unchanged

#### Scenario: Update enabled skills
- **WHEN** user sends `PUT /api/conversations/conv-abc` with `enabled_skills: [2, 4]`
- **THEN** system replaces the conversation's enabled_skills with `[2, 4]` and returns the updated object; `enabled_files` remains unchanged

#### Scenario: Update enabled files
- **WHEN** user sends `PUT /api/conversations/conv-abc` with `enabled_files: [10, 12]`
- **THEN** system replaces the conversation's enabled_files with `[10, 12]` and returns the updated object; `enabled_skills` remains unchanged

#### Scenario: Update both skills and files
- **WHEN** user sends `PUT /api/conversations/conv-abc` with `enabled_skills: [1, 3], enabled_files: [10, 12]`
- **THEN** system updates both fields and returns the updated object

---

### Requirement: Delete Conversation
The system SHALL delete a conversation and all its associated messages via `DELETE /api/conversations/:id`.

**Response**: `200 OK` with `{ ok: true }`.

#### Scenario: Delete conversation with messages
- **WHEN** user deletes a conversation that has 100 messages
- **THEN** system deletes all 100 messages in `conversation_messages`, then deletes the conversation record; returns `{ ok: true }`

#### Scenario: Delete non-existent conversation
- **WHEN** user tries to delete a conversation that does not exist
- **THEN** system returns `404 Not Found`

#### Scenario: Delete another user's conversation
- **WHEN** user tries to delete a conversation owned by another user
- **THEN** system returns `404 Not Found`
