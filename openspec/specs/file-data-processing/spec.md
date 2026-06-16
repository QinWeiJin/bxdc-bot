# Capability: file-data-processing

> **Purpose**: HTTP API endpoints for file data processing operations (Excel, Word, TXT) exposed as callable tools to the LLM through skill-gateway.

## Purpose

TBD - created from delta spec intelligent-file-center.

## Requirements

### Requirement: HTTP API Architecture
All data processing capabilities SHALL be implemented as standard HTTP REST API endpoints in skill-gateway.
APIs SHALL use Apache POI as the primary framework, with Python code packages as supplementary.
Each API SHALL accept standard JSON request parameters and return structured JSON results.
All results SHALL be returned through the existing Tool call result mechanism to agent-core, with agent-core serving as a transparent proxy.

### Requirement: OCR Image Text Extraction API
The system SHALL provide an `ocr_image` HTTP API that extracts text from image files.
Supported input formats SHALL be png, jpg, jpeg, bmp, tiff.
The API SHALL use Tess4J (Tesseract OCR Java encapsulation) with Chinese+English language data.

#### Scenario: OCR succeeds
- **WHEN** `ocr_image` is called with a valid image URL
- **THEN** extracted text is returned as a plain text string

#### Scenario: OCR not configured
- **WHEN** `ocr_image` is called but Tesseract OCR is not installed
- **THEN** the API returns an error message suggesting user install Tesseract

#### Scenario: Unsupported image format
- **WHEN** `ocr_image` is called with a GIF or WebP image
- **THEN** the API returns an error indicating unsupported format

### Requirement: Excel Read API
The system SHALL provide an HTTP API `excel_read` that reads data from an Excel file (via file URL or session file reference).
Output SHALL include sheet list, row count per sheet, and content preview.

### Requirement: Excel Write API
The system SHALL provide an HTTP API `excel_write` that writes data to a new or existing Excel file.
It SHALL support writing to specific cells, rows, or ranges.

### Requirement: Excel Filter API
The system SHALL provide an HTTP API `excel_filter` that filters rows by column conditions.
Supported operators SHALL include equals, not-equals, greater-than, less-than, contains, starts-with, ends-with.

### Requirement: Excel Sort API
The system SHALL provide an HTTP API `excel_sort` that sorts data by one or more columns.
Sort direction SHALL support ascending and descending.

### Requirement: Excel Aggregate API
The system SHALL provide an HTTP API `excel_aggregate` for statistical aggregation including sum, count, avg, min, max, median, std.
Group-by support SHALL be included.

### Requirement: Excel Pivot API
The system SHALL provide an HTTP API `excel_pivot` for pivot table analysis with configurable row, column, and value fields.

### Requirement: Excel Calculate API
The system SHALL provide an HTTP API `excel_calculate` that performs column-level arithmetic operations (add, subtract, multiply, divide).

### Requirement: Excel Select Columns API
The system SHALL provide an HTTP API `excel_select_columns` that selects specific columns from a dataset.

### Requirement: Excel Clean API
The system SHALL provide an HTTP API `excel_clean` for data cleaning (remove nulls, trim whitespace, normalize case).

### Requirement: Excel Merge API
The system SHALL provide an HTTP API `excel_merge` that merges multiple sheets or workbooks by common key columns.
Support inner join, left join, and union operations.

### Requirement: Excel Format Convert API
The system SHALL provide an HTTP API `excel_convert` that converts between xls, xlsx, csv formats.

### Requirement: Excel Apply Style API
The system SHALL provide an HTTP API `excel_apply_style` for conditional formatting and cell coloring.

### Requirement: Excel Validate API
The system SHALL provide an HTTP API `excel_validate` for data compliance validation against defined rules.

### Requirement: Word Read API
The system SHALL provide an HTTP API `word_read` that reads the full text content of a Word document.

### Requirement: Word Write API
The system SHALL provide an HTTP API `word_write` that creates or modifies a Word document.

### Requirement: Word Extract Content API
The system SHALL provide an HTTP API `word_extract_content` that extracts specific content sections (headings, paragraphs, tables, images).

### Requirement: Word Search Keyword API
The system SHALL provide an HTTP API `word_search_keyword` that searches for keywords in a Word document.
Results SHALL include surrounding context and page/chapter location.

### Requirement: Word Replace Text API
The system SHALL provide an HTTP API `word_replace_text` that replaces text in a Word document.

### Requirement: Word Template Fill API
The system SHALL provide an HTTP API `word_template_fill` that fills placeholders in a Word template with provided data.

### Requirement: TXT Read API
The system SHALL provide an HTTP API `txt_read` that reads text file content.

### Requirement: TXT Write API
The system SHALL provide an HTTP API `txt_write` that writes content to a text file.

### Requirement: TXT Keyword Lines API
The system SHALL provide an HTTP API `txt_keyword_lines` that extracts lines containing specific keywords.

### Requirement: TXT Regex API
The system SHALL provide an HTTP API `txt_regex` that applies regular expression matching/extraction.
