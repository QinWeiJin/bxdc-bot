## ADDED Requirements

### Requirement: Gateway-Side Conversation Compaction Endpoint

The system MUST expose a `POST /api/conversations/{conversationId}/compact`
endpoint on the gateway that accepts a list of chat messages, applies
compaction logic, and returns the compacted message list. The endpoint
MUST be authenticated via an `X-Internal-Token` request header that
matches the `INTERNAL_API_TOKEN` environment variable on the gateway.
A missing or mismatched token MUST cause the endpoint to respond with
HTTP 401.

#### Scenario: Successful compact with token
- **WHEN** the endpoint receives a request with a valid `X-Internal-Token`
  header and a non-empty `messages` array
- **THEN** the endpoint responds with HTTP 200 and a JSON body
  `{ messages: ChatMessage[], summaryApplied: boolean, summarySource?: 'user' | 'system' | 'cache' }`

#### Scenario: Reject request without token
- **WHEN** the endpoint receives a request without an `X-Internal-Token`
  header
- **THEN** the endpoint responds with HTTP 401 and does not invoke any
  summarization logic

#### Scenario: Reject request with wrong token
- **WHEN** the endpoint receives a request with an `X-Internal-Token`
  header that does not match the gateway's `INTERNAL_API_TOKEN` env var
- **THEN** the endpoint responds with HTTP 401

### Requirement: Turn-Count Triggered Compaction

The gateway compaction endpoint MUST count the number of L2 (user +
assistant) turns in the input messages. When the L2 turn count
exceeds `CONTEXT_COMPACTION_THRESHOLD` (default 48), the endpoint MUST
trigger the summarization pipeline; otherwise the endpoint MUST return
the input messages unchanged with `summaryApplied: false`. An L2 turn
is defined as one user message plus the corresponding assistant
response; tool_call and tool_result messages do NOT contribute to the
L2 turn count.

#### Scenario: Short conversation does not trigger
- **WHEN** the input messages contain 30 L2 turns
- **THEN** the endpoint returns the input messages unchanged with
  `summaryApplied: false`

#### Scenario: Long conversation triggers compaction
- **WHEN** the input messages contain 49 L2 turns
- **THEN** the endpoint invokes the summarization pipeline and returns
  the compacted messages with `summaryApplied: true`

#### Scenario: Custom threshold via env var
- **WHEN** `CONTEXT_COMPACTION_THRESHOLD=20` is set and the input
  messages contain 25 L2 turns
- **THEN** the summarization pipeline is triggered

#### Scenario: L2 turn counting excludes tool messages
- **WHEN** the input messages contain 20 user turns, 20 assistant
  turns, and 40 tool / tool_result messages (all interleaved)
- **THEN** the L2 turn count is 40 and summarization is NOT triggered
  (40 ≤ 48)

### Requirement: Tool, Skills, and Dream Messages Inviolability

The compaction logic MUST preserve every message whose role is `tool`,
`tool_result`, or `function`, or whose `source` field indicates a skill
/ async-task result, in its original form and position, regardless of
turn count. The compaction logic MUST also preserve every message
whose role is `system` and whose `content` contains the `梦境查询完成`
marker ("dream messages"). All such messages MUST NOT be summarized,
truncated, reordered, or dropped.

#### Scenario: Tool message preserved during compression
- **WHEN** summarization is triggered and the messages include a `tool`
  role message with 5000 tokens of tool output
- **THEN** the tool message is included in the response messages array
  with its original content unchanged and in its original position

#### Scenario: Async task result preserved during compression
- **WHEN** summarization is triggered and the messages include an
  `assistant` role message with `source = "async_task_result"`
  containing 4000 tokens of skill output
- **THEN** that message is preserved in full and is treated as part of
  the inviolable layer

#### Scenario: Dream system message preserved during compression
- **WHEN** summarization is triggered and the messages include a
  `system` role message whose `content` contains `梦境查询完成` and the
  total content is 3000 tokens
- **THEN** that dream message is preserved in full in the L0 layer
  regardless of its size

### Requirement: Layered Message Structure with recent-16 Preservation

The compaction logic MUST partition messages into three layers: **L0
(system, including dream messages)** which is always preserved, **L1
(tool / skills)** which is always preserved, and **L2 (user /
assistant)** which is the only layer subject to summarization. When
the L2 turn count exceeds the threshold (default 48), the system MUST
select the most recent `CONTEXT_COMPACTION_RECENT_K` L2 turns (default
16) for verbatim preservation, and MUST route **all other L2 turns**
(i.e., every L2 turn that is not in the recent 16) into the
summarization pipeline.

#### Scenario: 49 L2 turns routes 33 into summarization
- **WHEN** there are 49 L2 turns
- **THEN** the last 16 L2 turns are passed to the LLM unchanged and
  the other 33 L2 turns are routed into the summarization pipeline

#### Scenario: 100 L2 turns routes 84 into summarization
- **WHEN** there are 100 L2 turns
- **THEN** the last 16 L2 turns are passed to the LLM unchanged and
  the other 84 L2 turns are routed into the summarization pipeline

#### Scenario: At or below threshold skips summarization
- **WHEN** there are 30 L2 turns (≤ 48)
- **THEN** no summarization is performed and the original 30 L2 turns
  are all preserved

#### Scenario: Compaction order is L0 then L1 then summary then recent
- **WHEN** the compacted messages array is assembled
- **THEN** all L0 (system) messages appear first, followed by all L1
  (tool/skills) messages, followed by the summary block (single
  system role message with content "以下是历史摘要：\n<text>"),
  followed by the recent 16 L2 turns in chronological order

### Requirement: Summary Generation and DB Caching

The compaction logic MUST generate summaries by calling an LLM
selected as follows: first the user's own LLM configuration (apiKey,
apiBase, model), and falling back to the system default LLM when no
user configuration exists. The system MUST use a fixed prompt that
instructs the model to compress the input to 200-500 Chinese characters
while preserving key facts, decisions, and constraints.

The system MUST persist summaries in the
`conversation_message_summaries` table keyed by
`(conversation_id, covers_from_msg_id, covers_to_msg_id)` and MUST
reuse a cached summary when the same range is requested again.

#### Scenario: First-time summary uses user LLM
- **WHEN** summarization is triggered, no cached summary exists, and
  the user has an LLM configuration
- **THEN** the system calls the user's LLM with the fixed prompt,
  persists the result with the `model` column set to the user's model
  name, and returns the summary text

#### Scenario: First-time summary falls back to system LLM
- **WHEN** summarization is triggered, no cached summary exists, and
  the user has NO LLM configuration
- **THEN** the system calls the system default LLM with the fixed
  prompt, persists the result with the `model` column set to the
  system default model name

#### Scenario: Cache hit avoids LLM call
- **WHEN** summarization is triggered and a cached summary exists in
  `conversation_message_summaries` for the same
  `(conversation_id, covers_from_msg_id, covers_to_msg_id)` triple
- **THEN** the system MUST NOT call any LLM and MUST return
  `summarySource: 'cache'`

#### Scenario: Summary prompt language
- **WHEN** the system prompts the summarizer LLM
- **THEN** the prompt MUST instruct the model to use the same language
  as the input conversation and MUST explicitly forbid adding
  commentary, rephrasing meta-information, or fabricating content

### Requirement: Three-Tier Fallback Behavior (Suspended Compression, No Discard)

The compaction logic MUST implement three tiers of behavior: tier 1
(L2 turn count ≤ threshold, no compaction), tier 2 (L2 turn count >
threshold and summarization successful), tier 3 (L2 turn count >
threshold but summarization failed, unavailable, or disabled). Tier 3
MUST NOT attempt hard truncation by token count; it MUST instead
leave the pre-recent L2 region un-summarized (not represented in the
returned messages), preserving only L0, L1, and the recent 16 L2
turns. The endpoint MUST return `summaryApplied: false` and a
`summaryFailureReason` field. The system MUST NOT throw an error
when tier 2 cannot be reached.

#### Scenario: Tier 1 short conversation
- **WHEN** the L2 turn count is 30 (≤ 48)
- **THEN** the endpoint returns `summaryApplied: false` and the input
  messages unchanged

#### Scenario: Tier 2 with successful summary
- **WHEN** the L2 turn count is 60 AND the summarizer LLM call
  succeeds (or cache hits)
- **THEN** the endpoint returns `summaryApplied: true` and the
  compacted messages (L0 + L1 + summary + recent 16)

#### Scenario: Tier 3 summarization disabled
- **WHEN** the `CONTEXT_COMPACTION_ENABLED` env var is set to `false`
  AND the L2 turn count is 60
- **THEN** the endpoint returns `summaryApplied: false` and the
  compacted messages contain only L0 + L1 + recent 16 (the pre-recent
  L2 region is not represented); no LLM summarization call is made

#### Scenario: Tier 3 summarizer LLM fails
- **WHEN** the L2 turn count is 60 AND the summarizer LLM call
  raises any error
- **THEN** the endpoint logs a warning, sets `summaryFailureReason`
  to the error class, and returns `summaryApplied: false` with L0 +
  L1 + recent 16 (the pre-recent L2 region is not represented)

### Requirement: Agent-Core Thin Client Integration

The agent-core service MUST be modified minimally: at most one new file
(`src/services/gateway-compact-client.ts`, no more than ~50 lines) and
exactly one new line in `src/agent/agent.ts` that invokes the client
before passing messages to the LLM. The client MUST catch all
exceptions (HTTP errors, timeouts, parse errors) and fall back to
returning the original input messages, so that a gateway outage or
compact failure does NOT block the main chat pipeline. The client
MUST NOT add any new npm dependencies, MUST NOT add any new
environment variables, and MUST add no more than one new env var
(`INTERNAL_API_TOKEN` if not already present).

#### Scenario: Gateway returns compacted messages
- **WHEN** agent-core calls the gateway compact endpoint with messages,
  userId, and model
- **THEN** agent-core uses the returned `messages` array as the
  final input to the LLM

#### Scenario: Gateway endpoint is unavailable
- **WHEN** agent-core calls the gateway compact endpoint and the call
  fails (network error, HTTP 5xx, timeout after 3s)
- **THEN** the client logs a warning and returns the original input
  messages to the caller, and the main chat pipeline continues
  without compaction

#### Scenario: Gateway returns summaryApplied false
- **WHEN** agent-core calls the gateway compact endpoint and the
  response has `summaryApplied: false`
- **THEN** the client returns the `messages` field from the response
  (which may be smaller than the input, e.g., tier 3 fallback) to
  the caller, and the main chat pipeline continues

#### Scenario: No new npm dependencies in agent-core
- **WHEN** the change is applied
- **THEN** the agent-core `package.json` MUST NOT add any new
  dependencies (e.g., `lru-cache`, `gpt-tokenizer`, etc.); the client
  uses only packages already present in the project (e.g., `axios`)

#### Scenario: No new environment variables in agent-core (except possibly INTERNAL_API_TOKEN)
- **WHEN** the change is applied
- **THEN** the agent-core MUST NOT add any new environment variables
  beyond the single shared `INTERNAL_API_TOKEN` (which may already
  exist for other internal API calls)
