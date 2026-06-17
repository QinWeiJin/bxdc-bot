# python-execution-skill Specification

## Purpose
TBD - created by archiving change add-python-skill. Update Purpose after archive.
## Requirements
### Requirement: Python CONFIG Skill model

The system SHALL support a CONFIG-mode extended skill whose canonical configuration kind is `python`, and whose stored `configuration` object SHALL contain the normative fields `sandboxName` (reference to a `python_sandbox.name` row) and `operation` (LLM tool-name suffix). The system SHALL reject creation or update when `kind=python` is used without these two fields or when the referenced `sandboxName` row does not exist (in a separate write-time check).

#### Scenario: Persist python skill

- **WHEN** a user creates an extended skill with `executionMode` CONFIG and `configuration` containing `"kind": "python"`, a non-empty `sandboxName`, and a non-empty `operation`
- **THEN** the system persists the skill successfully through SkillGateway
- **AND** no `endpoint` / `method` / `command` / `prompt` fields are required for validation

#### Scenario: Reject missing sandboxName

- **WHEN** a create or update request uses `kind=python` with missing or whitespace-only `sandboxName`
- **THEN** the system rejects the request with a validation error

#### Scenario: Reject missing operation

- **WHEN** a create or update request uses `kind=python` with missing or whitespace-only `operation`
- **THEN** the system rejects the request with a validation error

### Requirement: Python skill runtime exposure to LLM

The agent extended-skill loader SHALL register a tool for each enabled `kind=python` skill using the same outer-`payload` Zod wrapper as `api` / `ssh` / `template` (no kind-specific branch in agent-core), and the Zod schema for the inner `payload` object SHALL be derived from the referenced `python_sandbox.service_params` JSON Schema (its `properties` / `required` / `type`) so that the LLM only sees fields the sandbox expects.

#### Scenario: Zod schema derived from sandbox service_params

- **WHEN** a `kind=python` skill's `sandboxName` references a row whose `service_params` declares `properties.script` (string) and `properties.args` (array of strings) with `required: ["script"]`
- **THEN** agent-core's Zod schema for this tool's inner payload is `z.object({ script: z.string(), args: z.array(z.string()).optional() })`
- **AND** the LLM is informed via the schema that `script` is mandatory and `args` is optional

#### Scenario: agent-core has no python-specific branch

- **WHEN** the LLM invokes a `kind=python` tool
- **THEN** agent-core MUST NOT contain any `if (config.kind === 'python')` branch in the tool invocation path
- **AND** the call MUST go through the same `POST /api/skills/execute` path as `api` / `ssh` / `template` extension skills

### Requirement: Python skill execution via external sandbox

The Gateway's `SkillExecutionService.execute()` MUST route `kind=python` skills to a new `executePythonSkill()` method that, on each invocation, resolves the referenced `python_sandbox` row, validates the inbound LLM parameters against `service_params` (the row's JSON Schema), and forwards the LLM parameters as the outbound JSON request body to the sandbox's `endpoint_url` using `http_method`. The Gateway MUST NOT rename, restructure, or inject fixed fields (such as `script` / `args`) into the outbound body — the field names are determined solely by the sandbox's `service_params`.

#### Scenario: Forward LLM parameters as body without renaming

- **WHEN** the LLM calls a `kind=python` tool with `{ payload: { code: "print('hi')", params: ["--v"] } }` and the referenced sandbox's `service_params` declares `code` and `params` as properties
- **THEN** the Gateway's outbound request body is `{ "code": "print('hi')", "params": ["--v"] }`
- **AND** the Gateway does NOT transform the field names (e.g. does NOT rename `code` to `script`)

#### Scenario: Reject unknown sandbox

- **WHEN** the skill's `sandboxName` does not match any `python_sandbox.name` row
- **THEN** the Gateway returns a 400 error with message containing the unknown sandbox name
- **AND** no outbound HTTP call is made

#### Scenario: Reject disabled sandbox

- **WHEN** the skill's `sandboxName` references a row with `enabled = 0`
- **THEN** the Gateway returns a 400 error indicating the sandbox is disabled
- **AND** no outbound HTTP call is made

### Requirement: service_params validation gate

The Gateway MUST validate the inbound LLM parameters against the sandbox's `service_params` JSON Schema (at minimum `required` and `type` constraints) before sending the outbound request. Validation failures MUST result in a 400 error and MUST NOT cause an outbound HTTP call.

#### Scenario: Missing required field

- **WHEN** `service_params.required` includes `"script"` but the LLM's payload does not contain a `script` field
- **THEN** the Gateway returns a 400 error naming the missing field
- **AND** no outbound HTTP call is made

#### Scenario: Field type mismatch

- **WHEN** `service_params.properties.script.type` is `"string"` but the LLM's payload provides a non-string value for `script`
- **THEN** the Gateway returns a 400 error indicating the expected vs actual type
- **AND** no outbound HTTP call is made

#### Scenario: Empty service_params allows passthrough

- **WHEN** `service_params` is the empty object `{}` (no `properties` and no `required`)
- **THEN** the Gateway performs no schema-based validation
- **AND** forwards the LLM payload as-is to the sandbox

### Requirement: Python skill audit and error semantics

The Gateway MUST record each outbound sandbox call to `gateway_outbound_audit_logs` using `HttpClientAuditMode.SKILL_OUTBOUND` with `skillContext="skill.python.sandboxName=<name>"` to enable per-sandbox audit filtering. Outbound HTTP errors (5xx, timeout) MUST be surfaced as a 500 response; sandbox-level business failures (e.g. `exitCode != 0` in the response body) MUST be passed through unchanged and are not treated as Gateway errors.

#### Scenario: Outbound 5xx surfaces as 500

- **WHEN** the sandbox returns HTTP 5xx
- **THEN** the Gateway returns 500 to the LLM with the underlying error message
- **AND** the failed call is recorded in `gateway_outbound_audit_logs`

#### Scenario: Sandbox business failure is not a Gateway error

- **WHEN** the sandbox returns HTTP 200 with a body indicating the script exited with non-zero code (e.g. `{ exitCode: 1, stderr: "..." }`)
- **THEN** the Gateway returns 200 to the LLM with the body unchanged
- **AND** this is NOT recorded as a Gateway error in audit logs

### Requirement: Python skill payload wrapper consistency

The Python skill type SHALL use the same outer `{ payload: ... }` Zod wrapper as `api` / `ssh` / `template` extension skills, and the Gateway's `executePythonSkill()` SHALL receive the LLM parameters with the outer `payload` already stripped by agent-core (no Gateway-side unwrap is required and no Gateway-side `payload` presence check is performed).

#### Scenario: Same payload wrapper as other CONFIG skills

- **WHEN** any CONFIG-mode extension skill (api / ssh / template / python) is invoked
- **THEN** the LLM sees the same Zod signature: `z.object({ payload: <innerSchema>.optional() }).passthrough()`
- **AND** the LLM calls the tool with `{ "payload": { ... } }`

#### Scenario: Gateway receives parameters without outer payload

- **WHEN** agent-core dispatches a `kind=python` skill call to the Gateway
- **THEN** the Gateway's `executePythonSkill()` receives the unwrapped parameters (the inner payload object) directly
- **AND** the Gateway does NOT need to perform a payload unwrap step

