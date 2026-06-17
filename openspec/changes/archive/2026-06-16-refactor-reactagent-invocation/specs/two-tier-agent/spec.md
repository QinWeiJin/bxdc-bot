## ADDED Requirements

### Requirement: Main Agent creation and initialization
The system SHALL provide a method to create a main Agent that loads user-defined skills and basic tools only.

#### Scenario: Create main Agent with user skills
- **WHEN** the system calls `AgentFactory.createMainAgent` with valid parameters
- **THEN** the system SHALL create an Agent instance with:
  - Basic tools: search_tools, execute_skill_with_context, skill_generator, compute, server_lookup, manage_tasks
  - User-defined skills from skills table where skill_owner_type=1
  - No system skills loaded

#### Scenario: Main Agent excludes system skills
- **WHEN** the main Agent is created
- **THEN** the system SHALL NOT load any skills where skill_owner_type=2

### Requirement: Sub Agent creation and initialization
The system SHALL provide a method to create a sub Agent that loads specific system skills dynamically.

#### Scenario: Create sub Agent with specific skill IDs
- **WHEN** the system calls `AgentFactory.createSubAgent` with skillIds parameter
- **THEN** the system SHALL create an Agent instance with:
  - Basic tools: compute, server_lookup, manage_tasks
  - System skills from skills table where skill_owner_type=2 AND id IN skillIds
  - No user-defined skills loaded

#### Scenario: Sub Agent filters by skill IDs
- **WHEN** skillIds contains [1, 2, 3]
- **THEN** the sub Agent SHALL load only skills with IDs 1, 2, and 3
- **AND** SHALL NOT load any other system skills

### Requirement: Skill on-demand loading
The system SHALL support loading skills based on owner type and skill IDs.

#### Scenario: Load user skills for main Agent
- **WHEN** the system loads skills with ownerType=1
- **THEN** the system SHALL query skills table WHERE skill_owner_type=1 AND enabled=true
- **AND** SHALL return all user-defined skills

#### Scenario: Load system skills for sub Agent
- **WHEN** the system loads skills with ownerType=2 and skillIds=[1, 2]
- **THEN** the system SHALL query skills table WHERE skill_owner_type=2 AND enabled=true AND id IN (1, 2)
- **AND** SHALL return only the specified system skills

### Requirement: Tool call result tracking
The system SHALL track and return all tool calls made by the sub Agent.

#### Scenario: Execute skill with context returns tool calls
- **WHEN** the execute_skill_with_context tool is invoked
- **THEN** the system SHALL return a JSON object containing:
  - status: "SUCCESS"
  - executedSkillIds: array of skill IDs
  - result: final output from sub Agent
  - toolCalls: array of tool call records with toolName, input, output, timestamp

#### Scenario: Tool call record structure
- **WHEN** a tool is called by the sub Agent
- **THEN** the toolCalls array SHALL contain a record with:
  - toolName: string (name of the tool)
  - input: object (tool input parameters)
  - output: string (tool output result)
  - timestamp: string (ISO 8601 format)

### Requirement: Search tools for system skills
The system SHALL provide a tool to search for system skills based on user query.

#### Scenario: Search tools returns system skills
- **WHEN** the search_tools tool is invoked with a query
- **THEN** the system SHALL call GET /api/skills/by-owner-type?ownerType=2
- **AND** SHALL return a JSON object containing:
  - status: "SUCCESS"
  - message: description of the search
  - skills: array of system skills with id, name, description, type, executionMode

#### Scenario: Search tools filters enabled skills only
- **WHEN** the search_tools tool queries system skills
- **THEN** the system SHALL return only skills where enabled=true
- **AND** SHALL NOT return disabled skills

### Requirement: Skill generator optimization
The system SHALL only create new skills when explicitly requested by the user.

#### Scenario: Skill generator creates skill on explicit request
- **WHEN** the user explicitly asks to create a new skill
- **THEN** the skill_generator tool SHALL create the skill
- **AND** SHALL return success status

#### Scenario: Skill generator does not auto-create
- **WHEN** the LLM cannot find a suitable skill for the task
- **THEN** the skill_generator tool SHALL NOT be called
- **AND** the system SHALL inform the user that no suitable skill is available