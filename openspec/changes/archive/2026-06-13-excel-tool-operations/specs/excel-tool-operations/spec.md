## ADDED Requirements

### Requirement: Excel Read Operation
The system SHALL provide a read operation to extract data from Excel files.

**Scenario:** User calls excel_read with fileId to retrieve Excel file contents
- Given an Excel file exists in the system
- When user invokes excel_read tool with the fileId
- Then the system returns headers and row data from the Excel file

### Requirement: Excel Write Operation
The system SHALL provide a write operation to create new Excel files.

**Scenario:** User calls excel_write to write data to a new Excel file
- Given user provides headers and row data
- When user invokes excel_write tool
- Then the system creates a new Excel file on FTP and returns the file download URL

### Requirement: Excel Filter Operation
The system SHALL provide a filter operation to select rows based on criteria.

**Scenario:** User calls excel_filter to filter rows by column value
- Given an Excel file with data
- When user invokes excel_filter with column, operator, and value
- Then the system returns filtered rows matching the criteria

### Requirement: Excel Sort Operation
The system SHALL provide a sort operation to order rows by columns.

**Scenario:** User calls excel_sort to sort data by a specific column
- Given an Excel file with data
- When user invokes excel_sort with column and order (asc/desc)
- Then the system returns sorted data

### Requirement: Excel Aggregate Operation
The system SHALL provide an aggregate operation for data summarization.

**Scenario:** User calls excel_aggregate to calculate statistics
- Given an Excel file with numeric columns
- When user invokes excel_aggregate with column and function (sum/avg/count/min/max)
- Then the system returns aggregated statistics

### Requirement: Excel Pivot Operation
The system SHALL provide a pivot operation for cross-tabulation analysis.

**Scenario:** User calls excel_pivot to create pivot table
- Given an Excel file with data
- When user invokes excel_pivot with row_col, col_col, and value_col
- Then the system returns pivot table data

### Requirement: Excel Calculate Operation
The system SHALL provide a calculate operation for column expressions.

**Scenario:** User calls excel_calculate to compute new column values
- Given an Excel file with data columns
- When user invokes excel_calculate with column_name and expression
- Then the system returns data with the new calculated column

### Requirement: Excel Select Columns Operation
The system SHALL provide a select_columns operation to pick specific columns.

**Scenario:** User calls excel_select_columns to select specific columns
- Given an Excel file with multiple columns
- When user invokes excel_select_columns with list of column names
- Then the system returns data with only the selected columns

### Requirement: Excel Clean Operation
The system SHALL provide a clean operation for data cleansing.

**Scenario:** User calls excel_clean to clean data
- Given an Excel file with duplicate rows or empty cells
- When user invokes excel_clean with options (removeDuplicates, removeEmpty)
- Then the system returns cleaned data

### Requirement: Excel Merge Operation
The system SHALL provide a merge operation for combining multiple files.

**Scenario:** User calls excel_merge to combine multiple Excel files
- Given multiple Excel files with compatible structure
- When user invokes excel_merge with list of fileIds
- Then the system returns merged data from all files

### Requirement: Excel Convert Format Operation
The system SHALL provide a convert_format operation.

**Scenario:** User calls excel_convert_format to change file format
- Given an Excel file in one format (xlsx/xls/csv)
- When user invokes excel_convert_format with targetFormat
- Then the system converts and saves the file in the new format, updating user_files table

### Requirement: Excel Apply Style Operation
The system SHALL provide an apply_style operation for conditional formatting.

**Scenario:** User calls excel_apply_style to apply styles
- Given an Excel file with data
- When user invokes excel_apply_style with column and style options
- Then the system returns styled Excel file

### Requirement: Excel Validate Operation
The system SHALL provide a validate operation for compliance checking.

**Scenario:** User calls excel_validate to check data validity
- Given an Excel file with data
- When user invokes excel_validate with validation rules
- Then the system returns validation results with any errors found

### Requirement: Multi-step Operation Chain
The system SHALL support chained operations across multiple tool calls.

**Scenario:** Temp file operations maintain fileId across operations
- Given a user creates a temp file using excel_init_temp
- When user performs multiple operations (filter, sort, etc.) using the temp fileId
- Then each operation uses the same temp file (overwrites the same file on FTP)
- And each operation returns the same temp fileId for subsequent operations

### Requirement: Temp File Overwrite Optimization
The system SHALL overwrite the same temp file during multi-step operations to save FTP storage space.

**Scenario:** Temp file overwrites itself during operations
- Given a temp file exists with sourceFileId set
- When user invokes any file-modifying operation
- Then the system overwrites the temp file using its storage filename (UUID)
- And returns the temp fileId, not the source fileId
