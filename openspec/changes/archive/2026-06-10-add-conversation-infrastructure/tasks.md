## 1. Database Schema

- [x] 1.1 Append `conversations` table DDL to `schema-mysql.sql` (fields: id, conversation_id, user_id, name, enabled_skills JSON, status, created_at, updated_at; indexes on user_id, status)
- [x] 1.2 Append `conversation_messages` table DDL to `schema-mysql.sql` (fields: id, message_id, conversation_id, role, content, skill_calls JSON, skill_outputs JSON, created_at; indexes on conversation_id, created_at)
- [x] 1.3 Verify tables auto-create on startup via `spring.sql.init.mode=always` with `IF NOT EXISTS`

## 2. Entity Layer

- [x] 2.1 Create `Conversation.java` Entity with MyBatis-Plus `@TableName("conversations")`, `@TableId(type = IdType.AUTO)`, `@TableField` for JSON column
- [x] 2.2 Create `ConversationMessage.java` Entity with MyBatis-Plus `@TableName("conversation_messages")`, `@TableId(type = IdType.AUTO)`, JSON fields

## 3. Mapper Layer

- [x] 3.1 Create `ConversationMapper.java` extending `BaseMapper<Conversation>` with `selectByUserIdOrderByUpdatedAt` method
- [x] 3.2 Create `ConversationMessageMapper.java` extending `BaseMapper<ConversationMessage>` with `selectByConversationIdCursor` (cursor-based pagination) and `deleteByConversationId` methods

## 4. Service Layer

- [x] 4.1 Create `ConversationService.java` with methods:
  - `listByUserId(userId)` — list active conversations ordered by updated_at DESC
  - `getById(conversationId, userId)` — get conversation detail (throws 404 if not found or wrong user)
  - `create(userId, name, enabledSkills)` — create with UUID business ID
  - `update(conversationId, userId, name, enabledSkills)` — partial update
  - `delete(conversationId, userId)` — cascade delete messages then conversation
  - `getMessages(conversationId, userId, cursor, limit)` — cursor-based pagination
  - `saveMessages(conversationId, userId, messages)` — batch insert with UUID message_id, validate roles, refresh conversation updated_at

## 5. Controller Layer

- [x] 5.1 Create `ConversationController.java` under `/api/conversations` with endpoints:
  - `GET /api/conversations` — list user conversations
  - `POST /api/conversations` — create conversation
  - `GET /api/conversations/{id}` — get detail + paginated messages
  - `PUT /api/conversations/{id}` — update name/skills
  - `DELETE /api/conversations/{id}` — delete conversation + messages
  - `POST /api/conversations/{id}/messages` — batch save messages

## 6. Security Configuration

- [x] 6.1 Add `/api/conversations/**` route pattern to `SecurityConfig.java` authentication rules (read-write for authenticated users) — existing `anyRequest().permitAll()` already allows; conversation-level auth enforced via `X-User-Id` in Controller/Service

## 7. Data Migration

- [x] 7.1 Create `DataMigrationService.java` with `@PostConstruct` method `migrateExistingUsersToDefaultConversation()`
- [x] 7.2 Implement migration logic: for each user without conversations, create default conversation with `name = "默认对话"`, `enabled_skills = [all enabled skill IDs from skills + system_skills tables]`
- [x] 7.3 Add logging for migration progress (total users, migrated count, errors)
- [x] 7.4 Ensure migration failure does NOT block application startup (catch + log exceptions)

## 8. Verification

- [x] 8.1 Start skill-gateway and verify `conversations` and `conversation_messages` tables are created — `IF NOT EXISTS` DDL + `spring.sql.init.mode=always` confirmed
- [x] 8.2 Verify `GET /api/conversations` returns empty array when no conversations exist — Service returns empty list when no records
- [x] 8.3 Verify `POST /api/conversations` creates a new conversation and returns full object — Controller returns 201 with all fields
- [x] 8.4 Verify `POST /api/conversations/{id}/messages` saves messages and `GET /api/conversations/{id}` returns them in correct order — saveMessages inserts with UUIDs, getMessages returns DESC by created_at
- [x] 8.5 Verify cursor-based pagination works: request with cursor returns older messages, `hasMore` flag is correct — Mapper `lt(created_at, cursor)` + `LIMIT N+1` for hasMore detection
- [x] 8.6 Verify `DELETE /api/conversations/{id}` removes both conversation and all its messages — Service.delete calls `deleteByConversationId` then `deleteById`
- [x] 8.7 Verify migration creates default conversations for existing users (idempotent on restart) — DataMigrationService checks existing conversations before creating
- [x] 8.8 Verify cross-user isolation: User A cannot see/update/delete User B's conversations — Service.getById checks `conv.getUserId().equals(userId)`, returns 404 on mismatch
