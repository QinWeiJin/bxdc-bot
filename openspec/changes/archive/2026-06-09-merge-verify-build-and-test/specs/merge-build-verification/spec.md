## ADDED Requirements

### Requirement: Java (skill-gateway) compilation passes
The system SHALL compile successfully via `mvn compile -q` with zero errors after the merge.

#### Scenario: Clean Maven compilation
- **WHEN** developer runs `cd backend/skill-gateway && mvn compile -q`
- **THEN** Maven compiles all Java source files with exit code 0 and no compilation errors

#### Scenario: Compilation error due to missing import
- **WHEN** a merged file references a class from a new entity/service/dto that was not carried over
- **THEN** `mvn compile` reports a compilation error with the unresolved symbol
- **THEN** developer MUST locate the missing file in lijianlong side and copy it over, or fix the import

#### Scenario: Compilation error due to method signature mismatch
- **WHEN** SkillExecutionService has different method signatures than what SkillController expects
- **THEN** `mvn compile` reports a method-not-found or incompatible-types error
- **THEN** developer MUST reconcile the two versions

### Requirement: TypeScript (agent-core) type check passes
The system SHALL pass TypeScript type checking via `npx tsc --noEmit` with zero errors.

#### Scenario: Clean type check
- **WHEN** developer runs `cd backend/agent-core && npx tsc --noEmit`
- **THEN** TypeScript compiler reports zero type errors and exit code 0

#### Scenario: Type error due to module structure mismatch
- **WHEN** java-skills.ts references types from skill-shared.ts / skill-generator.ts / openclaw-executor.ts that have changed
- **THEN** `tsc --noEmit` reports import-not-found or type-mismatch errors
- **THEN** developer MUST reconcile type definitions across modules

### Requirement: Frontend (Vite) production build passes
The system SHALL produce a valid frontend dist bundle via `npm run build` with no errors.

#### Scenario: Clean frontend build
- **WHEN** developer runs `cd frontend && npm run build`
- **THEN** Vite builds the project successfully with exit code 0 and outputs `dist/` directory

#### Scenario: Build error due to missing frontend file
- **WHEN** a Vue component or TypeScript module from lijianlong side was not copied over
- **THEN** Vite build reports a module-not-found error
- **THEN** developer MUST copy the missing frontend file from lijianlong branch

#### Scenario: Build error due to API type mismatch
- **WHEN** useChat.ts or skillEditor.ts have exports/types that don't match consuming components
- **THEN** Vite build reports type or import errors
- **THEN** developer MUST reconcile the frontend type definitions
