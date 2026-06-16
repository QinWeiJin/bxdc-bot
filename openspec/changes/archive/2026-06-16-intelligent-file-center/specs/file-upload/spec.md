## ADDED Requirements

### Requirement: File Upload via Click
The system SHALL support file upload via clicking an upload icon.
When the user clicks the icon, a file picker dialog SHALL open, allowing selection of word (.doc, .docx) and excel (.xls, .xlsx) files initially; csv, txt, md, py files SHALL also be supported.

#### Scenario: User clicks upload icon
- **WHEN** user clicks the upload icon in the chat interface
- **THEN** a native file picker opens allowing selection of supported file types

### Requirement: File Upload via Drag and Drop
The system SHALL support file upload by dragging files into the chat input area.

#### Scenario: User drags file into chat input
- **WHEN** user drags a supported file into the chat input area
- **THEN** the file is queued for upload and validation

### Requirement: File Upload via Ctrl+V (Paste)
The system SHALL support pasting files from clipboard into the chat input area via Ctrl+V as a secondary upload method.

#### Scenario: User pastes file with Ctrl+V
- **WHEN** user presses Ctrl+V with a supported file in clipboard in the chat input area
- **THEN** the file is queued for upload and validation

### Requirement: File Type Validation by Magic Number
The system SHALL validate uploaded files by their magic number (file signature), not just file extension.
Only doc, docx, xls, xlsx, csv, txt, and md files SHALL be accepted.

#### Scenario: User uploads a valid docx file
- **WHEN** user uploads a file whose magic number matches .docx
- **THEN** the system accepts the file

#### Scenario: User uploads a disallowed file type (e.g., .exe renamed to .docx)
- **WHEN** user uploads a file whose magic number indicates an unsupported type
- **THEN** the system SHALL show a popup: "当前仅支持doc、docx、xls、xlsx、csv、txt以及md文件的上传"

### Requirement: File Size Validation
The system SHALL validate that each uploaded file does not exceed 10MB.
For .py files, the limit SHALL also be 10MB per file.

#### Scenario: File size exceeds 10MB
- **WHEN** user uploads a file larger than 10MB
- **THEN** the system SHALL show a popup: "文件大小超过10MB，请修改后重试。"

#### Scenario: File size within limit
- **WHEN** user uploads a file of 5MB
- **THEN** the system SHALL accept the file

### Requirement: File Count Validation
The system SHALL limit the number of files per session to 5.
Users MAY upload up to 5 files in a single operation, or accumulate them across multiple upload actions within the same session.

#### Scenario: File count exceeds 5
- **WHEN** a session already has 5 files and user attempts to upload another
- **THEN** the system SHALL show a popup: "单次最多上传5个文件，请减少选择。"

### Requirement: Duplicate File Detection
The system SHALL detect duplicate files across all sessions for the same user directory.
When a file with the same name already exists, the system SHALL prompt the user for replacement.

#### Scenario: Duplicate file detected
- **WHEN** user uploads a file whose name already exists in their directory
- **THEN** the system SHALL show a popup: "该文件已于{upload_time}上传，是否进行替换？"

#### Scenario: User chooses to replace
- **WHEN** user clicks "Replace" on the duplicate prompt
- **THEN** the system SHALL delete the existing file and store the new one

#### Scenario: User chooses not to replace
- **WHEN** user clicks "Cancel" on the duplicate prompt
- **THEN** the system SHALL reject the new upload

### Requirement: Upload Progress Indicator
The system SHALL display a loading spinner during file upload and parsing.
When upload and parsing complete, the spinner SHALL disappear and the file indicator SHALL change color.

#### Scenario: File uploading
- **WHEN** a file is being uploaded
- **THEN** a spinning indicator is shown on the file entry

#### Scenario: File upload and parse complete
- **WHEN** file upload and parsing are both complete
- **THEN** the spinner disappears and the file entry color changes to indicate readiness

### Requirement: Cancel Upload
The system SHALL allow users to cancel uploaded files by clicking an "x" button on each file entry.
Files that have already been used in the conversation SHALL NOT be cancelable.

#### Scenario: User cancels a pending file
- **WHEN** user clicks "x" on a file that hasn't been used in conversation
- **THEN** the file is removed from the upload queue and will not be referenced in the conversation

#### Scenario: User tries to cancel a used file
- **WHEN** user clicks "x" on a file already referenced in conversation
- **THEN** the cancel action is ignored and the file remains

### Requirement: File Storage on FTP
The system SHALL store uploaded files in the user's dedicated FTP directory.
Files SHALL be named by their original filename.

#### Scenario: Successful file upload
- **WHEN** a file passes all validations
- **THEN** the file is stored on FTP at `/{user_aam_id}/{original_filename}`
