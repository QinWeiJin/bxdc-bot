## ADDED Requirements

### Requirement: Agent-core tests pass
The system SHALL pass all agent-core unit tests via `npm test` with zero failures.

#### Scenario: All tests pass
- **WHEN** developer runs `cd backend/agent-core && npm test`
- **THEN** all test suites complete with zero failures and exit code 0

#### Scenario: Test failure due to agent.ts behavior change
- **WHEN** tests that mock `AgentFactory.build()` have assertions on model configuration
- **THEN** test fails if `streaming` or `AGENT_STREAMING` changes break existing expectations
- **THEN** developer MUST update test expectations to match the merged code

#### Scenario: Test failure due to tool schema change
- **WHEN** tests validate tool schemas that now include `pollStrategy`/`singleCallReadTimeoutSeconds` fields
- **THEN** test must pass if it validates against the updated AsyncPollConfig interface
- **THEN** developer MUST update test assertions if they expect exact schema shapes

### Requirement: Skill-gateway tests pass
The system SHALL pass all skill-gateway unit tests via `mvn test` with zero failures.

#### Scenario: All Java tests pass
- **WHEN** developer runs `cd backend/skill-gateway && mvn test`
- **THEN** all test classes complete with zero failures and exit code 0

#### Scenario: Test failure due to service method change
- **WHEN** tests mock SkillController or SkillExecutionService methods that changed signatures
- **THEN** test fails with method-not-found or argument mismatch
- **THEN** developer MUST update test mocks to match the new service APIs

#### Scenario: Test failure due to new dependencies
- **WHEN** SkillExecutionService requires new dependencies (ApiProxyService, DedupConfig, etc.) that tests don't provide
- **THEN** test fails with NullPointerException or unsatisfied dependency
- **THEN** developer MUST add the missing mock/stub to the test setup
