## ADDED Requirements

### Requirement: Parse Excel file metadata
The system SHALL extract metadata from xls, xlsx, and csv files including sheet count, sheet names, row/column counts, and header information.

#### Scenario: Parse xlsx file
- **WHEN** system receives an xlsx file
- **THEN** system returns metadata including sheet count, sheet names, row/column counts, and header information

#### Scenario: Parse xls file
- **WHEN** system receives an xls file
- **THEN** system returns metadata including sheet count, sheet names, row/column counts, and header information

#### Scenario: Parse csv file
- **WHEN** system receives a csv file
- **THEN** system returns metadata including single sheet with column information

### Requirement: Infer column data types
The system SHALL automatically infer column data types (string, float, datetime, category) by sampling data.

#### Scenario: Detect numeric columns
- **WHEN** parsing a file with numeric data
- **THEN** system correctly identifies columns as float type

#### Scenario: Detect date columns
- **WHEN** parsing a file with date data in recognized formats
- **THEN** system correctly identifies columns as datetime type

### Requirement: Provide data preview
The system SHALL provide a preview of up to 10 rows of data for each sheet.

#### Scenario: Return limited rows
- **WHEN** requesting data preview
- **THEN** system returns at most 10 rows of data

### Requirement: File type routing
The system SHALL route files to appropriate parsers based on file extension.

#### Scenario: Route by extension
- **WHEN** a file is submitted for parsing
- **THEN** system selects the appropriate parser based on file extension (.xls, .xlsx, .csv)
