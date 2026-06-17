# python-sandbox-registry Specification

## Purpose

A new `python_sandbox` registry table stores per-environment third-party Python sandbox connection metadata. The registry is the single source of truth for sandbox `endpoint_url`, `http_method`, the LLM-input JSON Schema (`service_params`), and the enabled flag, decoupling sandbox wiring from individual `kind=python` skill rows and allowing operators to add or remove sandboxes at runtime without code changes.

## ADDED Requirements

### Requirement: python_sandbox table schema

The system MUST provide a `python_sandbox` table with at least the columns: `id` (auto-increment primary key), `name` (VARCHAR(64), unique, non-null — the value referenced by `Skill.configuration.sandboxName`), `endpoint_url` (VARCHAR(1024), non-null — full URL with scheme + host + path), `http_method` (VARCHAR(8), non-null, default `'POST'` — only `POST` / `PUT` accepted in the first version), `service_params` (TEXT, non-null, default `'{}'` — JSON Schema string describing the LLM's input parameters; must be a valid JSON object), `enabled` (TINYINT(1), non-null, default `1`), `description` (TEXT, nullable), and standard `created_at` / `updated_at` timestamps.

#### Scenario: Default row values

- **WHEN** a new row is inserted without specifying `http_method`, `service_params`, or `enabled`
- **THEN** the row is persisted with `http_method='POST'`, `service_params='{}'`, `enabled=1`

#### Scenario: Reject invalid service_params JSON

- **WHEN** an insert or update request provides `service_params` that is not a valid JSON object (e.g. malformed JSON, an array, a scalar)
- **THEN** the system rejects the request with a validation error
- **AND** the row is not persisted

#### Scenario: Reject duplicate name

- **WHEN** an insert request provides a `name` that already exists in `python_sandbox`
- **THEN** the system rejects the request with a uniqueness violation
- **AND** the existing row is not modified

### Requirement: Admin-only write access to python_sandbox

The system MUST restrict create / update / delete operations on `python_sandbox` to a single hard-coded admin user ID `890728` (matching `SKILL_PLATFORM_ADMIN_USER_ID` in `SkillService`). All other authenticated users MUST be denied write access. The system MUST expose read endpoints that return only `enabled=true` rows for non-admin users.

#### Scenario: Admin can create a sandbox

- **WHEN** a request to `POST /api/python-sandbox` includes the header `X-User-Id: 890728`
- **THEN** the system persists the row and returns 200 with the persisted view

#### Scenario: Non-admin cannot create a sandbox

- **WHEN** a request to `POST /api/python-sandbox` includes any other `X-User-Id` value
- **THEN** the system returns 403 Forbidden
- **AND** no row is created

#### Scenario: Non-admin sees only enabled sandboxes

- **WHEN** a request to `GET /api/python-sandbox?enabled=true` includes any non-admin `X-User-Id`
- **THEN** the system returns only rows with `enabled = 1`
- **AND** rows with `enabled = 0` are omitted from the response

### Requirement: python_sandbox integration with listExecutionTypes

The system MUST extend `GET /api/system-skills/execution-types` to additionally return, for each `python_sandbox` row with `enabled = 1`, an entry of `type: "python"`, `label: "<sandbox.name>: <sandbox.description>"`, and `configSchema` produced by `buildPythonConfigSchema()`. The hard-coded `api` / `ssh` / `template` entries MUST remain unchanged in their order, content, and behavior.

#### Scenario: Disabled sandbox is excluded from execution types

- **WHEN** a `python_sandbox` row has `enabled = 0`
- **THEN** no `type: "python"` entry for that row appears in the `GET /api/system-skills/execution-types` response

#### Scenario: Newly added enabled sandbox appears in execution types

- **WHEN** an admin creates a new `python_sandbox` row with `enabled = 1`
- **THEN** a subsequent call to `GET /api/system-skills/execution-types` includes a new `type: "python"` entry for that sandbox without requiring a server restart or front-end redeploy

### Requirement: python_sandbox response view does not leak write-only fields

The read endpoints (`GET /api/python-sandbox[/...]`) MUST return a stable view DTO. In the first version, the view MAY include all stored columns verbatim because no write-only secret field (such as `auth_token`) is stored; if a sensitive field is later added, it MUST be omitted from the view and returned only via an internal getter for outbound use.

#### Scenario: Public list view is stable

- **WHEN** a non-admin user calls `GET /api/python-sandbox?enabled=true`
- **THEN** each returned row contains at least `name`, `endpoint_url`, `http_method`, `service_params`, `description`
- **AND** no field that is intended for internal use only is exposed

### Requirement: python_sandbox errors surface as 400/403/404

The system MUST distinguish four error classes for `python_sandbox` operations: 400 (validation failure such as invalid JSON in `service_params` or non-POST/PUT `http_method` in the first version), 403 (non-admin attempting a write), 404 (referencing a `name` that does not exist on read/update/delete), 409 (uniqueness violation on `name`). Generic 500 errors MUST NOT be returned for these user-fixable cases.

#### Scenario: Unknown sandbox name on read

- **WHEN** a request to `GET /api/python-sandbox/{name}` uses a `name` that does not exist
- **THEN** the system returns 404

#### Scenario: Uniqueness violation on create

- **WHEN** a request to `POST /api/python-sandbox` uses a `name` that already exists
- **THEN** the system returns 409 with a message indicating the name is already taken
