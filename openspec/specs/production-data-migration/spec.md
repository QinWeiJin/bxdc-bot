## ADDED Requirements

### Requirement: Auto-Create Default Conversation on Startup
The system SHALL, on Spring Boot application startup, ensure every existing user has at least one default conversation.

**Behavior**: A `@PostConstruct` method in `DataMigrationService` iterates all users. For each user without any conversation in the `conversations` table, it creates a default conversation with:
- `name`: `"默认对话"`
- `enabled_skills`: an array of all Skill IDs that are currently `enabled = 1` in the `skills` table (Extension Skills) and `enabled = 1` in `system_skills` table (Built-in Skills), merged into a single array
- `status`: `active`

#### Scenario: First deployment with existing users
- **WHEN** system starts up with 3 users in `users` table, none of which have any conversation
- **THEN** system creates 1 default conversation for each user, each with `name = "默认对话"` and `enabled_skills` containing all currently enabled Skill IDs

#### Scenario: Subsequent deployment (idempotency)
- **WHEN** system restarts and all users already have at least 1 conversation from a previous migration
- **THEN** system does NOT create any new conversations; no duplicate conversations are created

#### Scenario: New deployment with no users
- **WHEN** system starts up on a fresh database with zero users
- **THEN** migration runs without error, creates no conversations

---

### Requirement: Migration Failure Must Not Block Application
The system SHALL handle migration failures gracefully, logging the error but allowing the application to continue starting.

#### Scenario: Database connection fails during migration
- **WHEN** migration encounters a database error (e.g., connection timeout)
- **THEN** system logs the error at ERROR level and continues startup; the application remains functional (users without conversations will see an empty state)

---

### Requirement: Migration Logging
The system SHALL log migration progress including: total users processed, users that needed migration, and any errors encountered.

#### Scenario: Successful migration
- **WHEN** migration completes with 5 users migrated out of 10 total (5 already had conversations)
- **THEN** system logs: `[DataMigration] Processed 10 users, created default conversations for 5 users`

#### Scenario: Partial failure
- **WHEN** migration succeeds for 4 users but fails for 1 user due to a transient error
- **THEN** system logs the failed user ID and error; successfully migrated users are not rolled back
