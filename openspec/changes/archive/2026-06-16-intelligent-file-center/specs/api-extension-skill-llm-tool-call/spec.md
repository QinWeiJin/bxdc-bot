## ADDED Requirements

### Requirement: File Operation HTTP API Registration as LLM Tool
The system SHALL register all file operation API endpoints (modules 4 and 5) as callable Tools in the skill-gateway.
The LLM SHALL be able to discover and invoke these APIs through the existing HTTP Tool call protocol.
All API execution results SHALL return structured JSON and pass through agent-core to the LLM.

#### Scenario: LLM discovers file APIs
- **WHEN** LLM queries available tools
- **THEN** all file operation APIs (excel_read, word_search_keyword, file_list, etc.) are listed

#### Scenario: LLM invokes a file API
- **WHEN** LLM invokes `excel_aggregate` with a file reference and aggregation parameters
- **THEN** the skill-gateway executes the operation and returns structured results

### Requirement: File Download URL in API Responses
The system SHALL include a downloadable URL field in API responses where a file has been modified or is available for download.

#### Scenario: API response includes download URL
- **WHEN** an API modifies or creates a file
- **THEN** the response JSON includes a `download_url` field pointing to the result file
