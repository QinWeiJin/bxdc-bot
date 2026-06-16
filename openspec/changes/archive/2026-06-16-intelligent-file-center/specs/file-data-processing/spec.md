## ADDED Requirements

### Requirement: HTTP API Architecture
All data processing capabilities SHALL be implemented as standard HTTP REST API endpoints in skill-gateway.
APIs SHALL use Apache POI as the primary framework, with Python code packages as supplementary.
Each API SHALL accept standard JSON request parameters and return structured JSON results.
All results SHALL be returned through the existing Tool call result mechanism to agent-core, with agent-core serving as a transparent proxy.

### Requirement: OCR Image Text Extraction API
The system SHALL provide an `ocr_image` HTTP API that extracts text from image files.
Supported input formats SHALL be png, jpg, jpeg, bmp, tiff.
The API SHALL use Tess4J (Tesseract OCR Java封装) with Chinese+English language data.

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

### Requirement: TXT Line Range API
The system SHALL provide an HTTP API `txt_line_range` that extracts a specific range of lines.

### Requirement: TXT Section API
The system SHALL provide an HTTP API `txt_section` that extracts content by Markdown-style heading sections.

### Requirement: TXT Stats API
The system SHALL provide an HTTP API `txt_stats` that returns character count, word count, and line count.

### Requirement: TXT Distinct Lines API
The system SHALL provide an HTTP API `txt_distinct_lines` that deduplicates lines in a text file.

### Requirement: TXT Sort Lines API
The system SHALL provide an HTTP API `txt_sort_lines` that sorts lines alphabetically or numerically.

### Requirement: TXT Keyword Frequency API
The system SHALL provide an HTTP API `txt_keyword_freq` that calculates keyword frequency statistics.

### Requirement: MD Read API
The system SHALL provide an HTTP API `md_read` that reads Markdown file content.

### Requirement: MD Write API
The system SHALL provide an HTTP API `md_write` that writes content to a Markdown file.

### Requirement: MD Images API
The system SHALL provide an HTTP API `md_images` that extracts all image references from Markdown.

### Requirement: MD Headings API
The system SHALL provide an HTTP API `md_headings` that extracts all headings with their hierarchy levels.

### Requirement: MD Table API
The system SHALL provide an HTTP API `md_table` that extracts all tables from Markdown.

### Requirement: MD List Items API
The system SHALL provide an HTTP API `md_list_items` that extracts all list items (ordered and unordered).

### Requirement: MD Tasks API
The system SHALL provide an HTTP API `md_tasks` that extracts all task list items (checkbox items).

### Requirement: MD Emphasis API
The system SHALL provide an HTTP API `md_emphasis` that extracts bold and italic text.

### Requirement: MD TOC API
The system SHALL provide an HTTP API `md_toc` that extracts or generates a table of contents.

### Requirement: MD Filter Section API
The system SHALL provide an HTTP API `md_filter_section` that removes or keeps specified sections by heading.

### Requirement: MD Merge API
The system SHALL provide an HTTP API `md_merge` that concatenates multiple Markdown files, deduplicating frontmatter.
