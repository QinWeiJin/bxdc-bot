# Capability: file-upload-dedup

> **Purpose**: When a user uploads a file with the same name as an existing file, provide a "overwrite" path that cleans up the old FTP file and old `user_files` row, then uploads the new file as the sole record.

## ADDED Requirements

### Requirement: Overwrite parameter on upload endpoint

The `POST /api/files/upload` endpoint SHALL accept an optional query parameter `overwrite` (boolean, default `false`). When `overwrite=false` (default), the existing "add new copy" behavior SHALL be preserved unchanged.

#### Scenario: Default upload behavior unchanged
- **WHEN** user uploads `report.docx` with no `overwrite` parameter (or `overwrite=false`)
- **THEN** the system inserts a new `user_files` row with a new UUID `file_name`
- **AND** the old `report.docx` row and FTP file are NOT touched

### Requirement: Overwrite removes old FTP file

When `overwrite=true` and a previous `user_files` row exists for the same `(user_id, original_file_name)`, the system SHALL delete the old FTP file via `FtpFileService.deleteFile()` BEFORE inserting the new row.

#### Scenario: Old FTP file deleted during overwrite
- **WHEN** user uploads `report.docx` with `overwrite=true` and a previous row with `file_name=abc-uuid.docx` exists
- **THEN** the system calls `ftpFileService.deleteFile(userId, "abc-uuid.docx")`
- **AND** the old FTP file SHALL be removed from the user's FTP directory

#### Scenario: FTP delete failure aborts overwrite
- **WHEN** user uploads `report.docx` with `overwrite=true` and the FTP delete call throws
- **THEN** the system SHALL return HTTP 502 with `FTP_UNAVAILABLE` error code
- **AND** the new file SHALL NOT be uploaded
- **AND** the old `user_files` row SHALL remain unchanged

### Requirement: Overwrite removes old user_files row

When `overwrite=true` and the old FTP file is successfully deleted, the system SHALL delete the old `user_files` row (by primary key `id`) BEFORE inserting the new row.

#### Scenario: Old user_files row deleted during overwrite
- **WHEN** user uploads `report.docx` with `overwrite=true` and old row with `id=42` exists
- **THEN** the system calls `userFileMapper.deleteById(42)`
- **AND** the new row is inserted as the sole record for `(user_id, "report.docx")`

#### Scenario: Foreign key references to old row
- **WHEN** other tables (e.g. `enabled_files`) reference the old `user_files.id` via foreign key
- **THEN** the delete SHALL succeed (via `ON DELETE CASCADE` on the FK, OR explicit cleanup in the controller)
- **AND** the system SHALL NOT leave orphan references

### Requirement: Overwrite is per-user isolated

The overwrite logic SHALL be scoped to the authenticated user only. User A's `overwrite=true` upload MUST NOT affect User B's files.

#### Scenario: Cross-user isolation
- **WHEN** User A uploads `report.docx` with `overwrite=true` and User B also has a `report.docx` row
- **THEN** only User A's old row and FTP file are deleted
- **AND** User B's row and FTP file remain intact

### Requirement: New row keeps the same original_file_name

When overwriting, the new `user_files` row SHALL have the same `original_file_name` as the old row, and a new UUID `file_name` and updated `upload_time`.

#### Scenario: Original filename preserved
- **WHEN** user overwrites `report.docx`
- **THEN** the new row's `original_file_name` is `report.docx`
- **AND** the new row's `file_name` is a fresh UUID
- **AND** the new row's `upload_time` is the current timestamp

### Requirement: Check-duplicate endpoint unchanged

The existing `GET /api/files/check-duplicate?fileName=xxx` endpoint SHALL continue to return `{ exists: boolean, uploadTime: string|null }` for the authenticated user, so the frontend can decide whether to show the overwrite confirmation dialog.

#### Scenario: Frontend uses check-duplicate to detect collision
- **WHEN** user selects `report.docx` for upload
- **THEN** the frontend calls `GET /api/files/check-duplicate?fileName=report.docx`
- **AND** if `exists=true`, the frontend shows a confirmation dialog
- **AND** if user confirms, the frontend re-uploads with `overwrite=true`
