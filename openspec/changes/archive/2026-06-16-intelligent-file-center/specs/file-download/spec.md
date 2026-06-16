## ADDED Requirements

### Requirement: Download Modified File After Tool Processing
The system SHALL provide a download link for files that have been modified by data processing tools.
When a tool like `excel_filter` or `word_replace_text` modifies a file, the result SHALL be downloadable.

#### Scenario: Modified file download available
- **WHEN** user applies a data processing tool that modifies a file
- **THEN** the tool response includes a download URL for the modified file

### Requirement: Explicit Download Request
The system SHALL support explicit download requests from the user.
When the user explicitly requests to download a file (e.g., "下载处理后的文件", "生成并下载报表"), the system SHALL provide a download link.

#### Scenario: User requests download explicitly
- **WHEN** user says "下载处理后的文件"
- **THEN** the system provides a direct download URL

### Requirement: Access Control for Downloads
The system SHALL verify that the requesting user has permission to download the file.
Download URLs SHALL only work for the file's owner.

#### Scenario: Unauthorized download attempt
- **WHEN** user attempts to download a file they do not own
- **THEN** the system returns 403 Forbidden
