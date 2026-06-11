# Capability: file-management

> **Purpose**: File management operations (list, delete, clear, view details) for user-uploaded files, with confirmation flows.

## Purpose

TBD - archived from delta spec intelligent-file-center.

## Requirements

## ADDED Requirements

### Requirement: List User Files
The system SHALL provide an HTTP API that lists all files in the user's dedicated FTP directory.
When the user's conversation mentions "显示我上传的文件", "文件列表", "我有哪些文件", or "列出文件", the LLM SHALL invoke this tool.
The response SHALL include filename, file size, and upload time for each file.

#### Scenario: User asks for file list
- **WHEN** user says "显示我上传的文件"
- **THEN** the system returns a list of all files with name, size, and upload time

#### Scenario: Empty directory
- **WHEN** user has no uploaded files
- **THEN** the system returns a message indicating no files found

### Requirement: File Download URL Field
The system SHALL include a download URL field for each file when presenting file information to the LLM.

#### Scenario: File list with download URLs
- **WHEN** the file list tool returns data
- **THEN** each file entry includes a download_url field

### Requirement: Delete File with Confirmation
The system SHALL allow users to delete files via conversation.
Before deletion, the system SHALL require explicit confirmation by asking the user to verify the filename.

#### Scenario: User requests file deletion
- **WHEN** user says "删除文件 xxx.docx"
- **THEN** the system asks for confirmation: "请确认是否删除文件 xxx.docx？"

#### Scenario: User confirms deletion
- **WHEN** user confirms the deletion with the correct filename
- **THEN** the file is permanently deleted from FTP

#### Scenario: User cancels deletion
- **WHEN** user does not confirm or says "取消"
- **THEN** no action is taken, file remains

### Requirement: Clear All Files with Confirmation
The system SHALL allow users to clear all files in their directory via conversation.
The confirmation flow SHALL be the same as single file deletion: explicit filename verification required.

#### Scenario: User requests clearing all files
- **WHEN** user requests to clear all files
- **THEN** the system asks for explicit confirmation before proceeding

### Requirement: View File Details
The system SHALL allow users to view details of a specific file via conversation.
Details SHALL include: filename, file size, upload time, file type, and a content summary from the parsing result.
