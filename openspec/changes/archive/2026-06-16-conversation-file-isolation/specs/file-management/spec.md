## MODIFIED Requirements

### Requirement: List User Files
The system SHALL provide a tool that lists files in the current conversation's scope. When `enabled_files` is set, only files whose IDs appear in that list SHALL be returned. When `enabled_files` is NULL (backward-compatible), all user files SHALL be returned.

When the user's conversation mentions "显示我上传的文件", "文件列表", "我有哪些文件", or "列出文件", the LLM SHALL invoke this tool.
The response SHALL include filename, file size, and upload time for each file in scope.

#### Scenario: User asks for file list in isolated conversation
- **WHEN** user says "显示我上传的文件" in a conversation with `enabled_files = [5, 8]`
- **THEN** the system only returns file data for fileId 5 and 8

#### Scenario: User asks for file list in backward-compatible conversation
- **WHEN** user says "显示我上传的文件" in a conversation where `enabled_files` is NULL
- **THEN** the system returns all user files (backward-compatible behavior)

#### Scenario: Enabled files empty
- **WHEN** conversation's `enabled_files = []`
- **THEN** the system returns an empty list with a message indicating no files are available in this conversation

---

### Requirement: Delete File with Conversation Scope Check
The system SHALL allow users to delete files via conversation, but the file MUST be within the current conversation's scope (present in `enabled_files` or `enabled_files` is NULL). Before deletion, the system SHALL require explicit confirmation by asking the user to verify the filename.

#### Scenario: User requests file deletion within scope
- **WHEN** user says "删除文件 xxx.docx" and file is in the conversation's `enabled_files`
- **THEN** the system asks for confirmation: "请确认是否删除文件 xxx.docx？"

#### Scenario: User requests file deletion outside scope
- **WHEN** user says "删除文件 yyy.xlsx" but file is NOT in the conversation's `enabled_files`
- **THEN** the system returns an error: "文件 yyy.xlsx 不在当前会话权限内"

#### Scenario: User confirms deletion
- **WHEN** user confirms the deletion with the correct filename
- **THEN** the file is permanently deleted from FTP

#### Scenario: User cancels deletion
- **WHEN** user does not confirm or says "取消"
- **THEN** no action is taken, file remains

---

### Requirement: Clear All Files with Conversation Scope
The system SHALL allow users to clear files within the current conversation's scope. When `enabled_files` is set, only those files SHALL be cleared. When `enabled_files` is NULL, all user files SHALL be cleared (backward-compatible). The confirmation flow SHALL be the same as single file deletion: explicit verification required.

#### Scenario: User requests clearing files in isolated conversation
- **WHEN** user requests to clear all files in a conversation with `enabled_files = [5, 8]`
- **THEN** only files 5 and 8 are deleted; the system asks for confirmation before proceeding

#### Scenario: User requests clearing files in backward-compatible conversation
- **WHEN** user requests to clear all files in a conversation where `enabled_files` is NULL
- **THEN** all user files are cleared (backward-compatible behavior)

---

### Requirement: View File Details with Scope Check
The system SHALL allow users to view details of a file, but the file MUST be within the current conversation's scope. Details SHALL include: filename, file size, upload time, file type, and a content summary from the parsing result.

#### Scenario: View file within scope
- **WHEN** user asks for details of a file in the conversation's `enabled_files`
- **THEN** the system returns the file's full details

#### Scenario: View file outside scope
- **WHEN** user asks for details of a file NOT in the conversation's `enabled_files`
- **THEN** the system returns an error: "文件不在当前会话权限内"

## ADDED Requirements

### Requirement: Automatic File Binding on Upload
When a file is uploaded with a `conversationId` parameter, the system SHALL automatically append the new file's ID to that conversation's `enabled_files` list, making the file immediately available in that conversation without requiring manual configuration.

#### Scenario: Upload with conversation context
- **WHEN** user uploads `report.xlsx` while currently viewing conversation `uuid-A`
- **THEN** the file's ID is appended to conversation `uuid-A`'s `enabled_files`
- **AND** `file_list` in conversation `uuid-A` SHALL include `report.xlsx`

#### Scenario: Upload without conversation context
- **WHEN** user uploads a file without an active conversation context
- **THEN** the file is saved normally, but not bound to any conversation
