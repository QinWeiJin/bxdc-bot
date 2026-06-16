# Capability: file-user-isolation

> **Purpose**: User-level file isolation using AAM token authentication, with automatic FTP directory creation and cross-user access prevention.

## Purpose

TBD - created from delta spec intelligent-file-center.

## Requirements

### Requirement: AAM Token Authentication
The system SHALL use AAM (unified authentication) token as the unique user identifier for all file operations.
The system SHALL extract the user identity from the request's Authorization header (AAM token).

#### Scenario: New user login
- **WHEN** a new user logs in with a valid AAM token
- **THEN** the system SHALL automatically create a user directory named by the AAM user ID on the FTP server

#### Scenario: Invalid or missing token
- **WHEN** a request is received without a valid AAM token
- **THEN** the system SHALL return HTTP 401 Unauthorized

### Requirement: FTP User Directory Auto-Creation
The system SHALL automatically create a directory named by the user's AAM ID on the FTP server when a new user performs their first file operation.
The directory name SHALL be the user's unified authentication ID.

#### Scenario: First-time user uploads a file
- **WHEN** a user with AAM ID "zhangsan" uploads their first file
- **THEN** the system SHALL create the directory `/zhangsan/` on the FTP server before storing the file

#### Scenario: Existing user uploads a file
- **WHEN** an existing user uploads a file
- **THEN** the system SHALL store the file directly in their existing directory

### Requirement: Cross-User File Access Forbidden
The system SHALL restrict each user to only view and operate on files within their own FTP directory.
No user SHALL be able to access files belonging to another user.

#### Scenario: User lists their files
- **WHEN** user "zhangsan" requests file listing
- **THEN** the system SHALL only return files from `/zhangsan/` directory

#### Scenario: User attempts to access another user's file
- **WHEN** user "lisi" tries to access a file under `/zhangsan/`
- **THEN** the system SHALL reject the request with HTTP 403 Forbidden
