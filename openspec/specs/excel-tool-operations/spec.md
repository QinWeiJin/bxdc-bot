# excel-tool-operations Specification

## Purpose
TBD - created by archiving change excel-tool-operations. Update Purpose after archive.
## Requirements
### Requirement: Excel Read Operation
The system SHALL provide a read operation to extract data from Excel files.

#### Scenario: Read Excel content
- **WHEN** user calls read operation with file ID
- **THEN** system returns JSON representation of the sheet data

### Requirement: Excel Write Operation
The system SHALL provide a write operation to create new Excel files.

#### Scenario: Write data to new file
- **WHEN** user calls write operation with data and file name
- **THEN** system creates new Excel file in FTP and returns download URL

### Requirement: Excel Filter Operation
The system SHALL provide a filter operation to select rows based on criteria.

#### Scenario: Filter by single condition
- **WHEN** user calls filter operation with column, operator, and value
- **THEN** system returns filtered rows

### Requirement: Excel Sort Operation
The system SHALL provide a sort operation to order rows by columns.

#### Scenario: Sort by multiple columns
- **WHEN** user calls sort operation with columns and orders
- **THEN** system returns sorted rows

### Requirement: Excel Aggregate Operation
The system SHALL provide an aggregate operation for data summarization.

#### Scenario: Group by and aggregate
- **WHEN** user calls aggregate operation with groupBy and aggregations
- **THEN** system returns aggregated results

### Requirement: Excel Pivot Operation
The system SHALL provide a pivot operation for cross-tabulation analysis.

#### Scenario: Create pivot table
- **WHEN** user calls pivot operation with rows, columns, and values
- **THEN** system returns pivot table results

### Requirement: Excel Calculate Operation
The system SHALL provide a calculate operation for column expressions.

#### Scenario: Compute new column
- **WHEN** user calls calculate operation with expression
- **THEN** system returns data with computed column

### Requirement: Excel Select Columns Operation
The system SHALL provide a select_columns operation to pick specific columns.

#### Scenario: Select specific columns
- **WHEN** user calls select_columns operation with column list
- **THEN** system returns only selected columns

### Requirement: Excel Clean Operation
The system SHALL provide a clean operation for data cleansing.

#### Scenario: Clean data
- **WHEN** user calls clean operation with rules
- **THEN** system returns cleaned data

### Requirement: Excel Merge Operation
The system SHALL provide a merge operation for combining multiple files.

#### Scenario: Merge multiple files
- **WHEN** user calls merge operation with file IDs and join key
- **THEN** system returns merged data

### Requirement: Excel Convert Format Operation
The system SHALL provide a convert_format operation.

#### Scenario: Convert between formats
- **WHEN** user calls convert_format operation with target format
- **THEN** system returns converted file download URL

### Requirement: Excel Apply Style Operation
The system SHALL provide an apply_style operation for conditional formatting.

#### Scenario: Apply conditional formatting
- **WHEN** user calls apply_style operation with style rules
- **THEN** system returns styled file download URL

### Requirement: Excel Validate Operation
The system SHALL provide a validate operation for compliance checking.

#### Scenario: Validate data compliance
- **WHEN** user calls validate operation with rules
- **THEN** system returns validation results

### Requirement: Multi-step Operation Chain
The system SHALL support chained operations across multiple tool calls.

#### Scenario: Chained operations
- **WHEN** user calls multiple operations with same session ID
- **THEN** system maintains state and applies operations sequentially

