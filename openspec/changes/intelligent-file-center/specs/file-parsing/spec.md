## ADDED Requirements

### Requirement: File Type Routing
The system SHALL route uploaded files to the appropriate parser based on file type.
Supported types: doc, docx (Word), xls, xlsx, csv (Excel), txt, md (Markdown), py (Python), pdf (PDF via Tika+PDFBox).

#### Scenario: Word file uploaded
- **WHEN** a .docx file is uploaded
- **THEN** the Word parser (Apache POI XWPF/HWPF) SHALL be invoked

#### Scenario: Excel file uploaded
- **WHEN** a .xlsx file is uploaded
- **THEN** the Excel parser (Apache POI XSSFWorkbook) SHALL be invoked

#### Scenario: PDF file uploaded
- **WHEN** a .pdf file is uploaded
- **THEN** the PDF parser (Apache Tika + Apache PDFBox) SHALL be invoked for full text extraction

### Requirement: Word Document Parsing
The system SHALL parse Word documents and extract titles, chapter headings (level 1, level 2, etc.), and content.
If no chapter headings exist, the parser SHALL fallback to large-font content, then numbered content, then the first 500 characters.
PDF files SHALL also be handled by the Word parsing module with Tika-based full text extraction (no chapter hierarchy).

#### Scenario: Word document with headings
- **WHEN** a Word document contains chapter headings
- **THEN** the parser extracts outline hierarchy (level, text, paragraph_index, char_count, children)

#### Scenario: Word document without headings
- **WHEN** a Word document has no heading styles
- **THEN** the parser falls back to large-font text as title, then numbered paragraphs, then first 500 chars

#### Scenario: PDF document
- **WHEN** a PDF document is uploaded
- **THEN** the parser extracts full text via PDFBox, splits by blank lines into paragraphs, takes first 200 chars as title

#### Scenario: OCR image processing
- **WHEN** an image file (png/jpg/jpeg/bmp/tiff) is uploaded and OCR is configured
- **THEN** the system SHALL call Tess4J OCR to extract text content

#### Scenario: OCR not configured
- **WHEN** an image file is uploaded but Tesseract OCR is not installed
- **THEN** the system SHALL notify the user that OCR is not available

### Requirement: Word Parse Output Format
The Word/PDF parser SHALL produce JSON with the following structure:
- file_type: "word" or "pdf"
- Content metadata: paragraph_count, table_count, image_count, page_estimate (Word only)
- outline: array of {level, text, paragraph_index, children[...], char_count}
- tables: array of {table_index, paragraph_index, row_count, col_count, has_header, header_text[], location_description}
- sections: array of {heading, level, paragraph_index, total_chars, has_tables, has_images}
- images: array of {image_index, paragraph_index, width_px, height_px}
- has_macro, is_encrypted flags

#### Scenario: Word parse completes
- **WHEN** Word parsing finishes successfully
- **THEN** a JSON object matching the format specification is returned

### Requirement: Excel Document Parsing
The system SHALL parse Excel files extracting sheet names, column headers, data types, and statistics.

#### Scenario: Excel with headers
- **WHEN** an Excel file has column headers
- **THEN** each column includes header_name, inferred_type, null_count, unique_count, and sample_values

#### Scenario: Excel without headers
- **WHEN** an Excel file has no clear headers
- **THEN** first 10 rows of data are extracted

### Requirement: Excel Parse Output Format
The Excel parser SHALL produce JSON with:
- file_type: "excel"
- sheet_count, sheets: array of {sheet_name, sheet_index, row_count, col_count, has_header, header_row_index, columns[...], has_formula, has_merged_cells, merged_cell_ranges[], has_chart, has_pivot_table, data_row_count, frozen_panes}
- Each column: {col_index, col_letter, header_name, inferred_type, null_count, unique_count, sample_values[], stats{min, max, mean, median, std, negative_count} [for numeric], min_date/max_date [for datetime], categories/category_counts [for category] }
- has_macro, is_encrypted flags

#### Scenario: Excel parse completes
- **WHEN** Excel parsing finishes successfully
- **THEN** a JSON object matching the format specification is returned

### Requirement: CSV Parsing
The system SHALL parse CSV files with automatic delimiter detection and encoding detection.

#### Scenario: CSV parse completes
- **WHEN** CSV parsing finishes
- **THEN** JSON with file_type "csv", delimiter_detected, encoding_detected, row_count, col_count, columns[/* same as Excel columns */], has_header is returned

### Requirement: TXT and MD Parsing
The system SHALL parse TXT and MD files by extracting the first 500 characters as a preview summary.

#### Scenario: TXT file parse
- **WHEN** a .txt file is parsed
- **THEN** first 500 characters are extracted as content summary

### Requirement: Python File Full Parsing
The system SHALL parse .py files with full content (no truncation or sampling).
Up to 5 .py files SHALL be supported, each not exceeding 10MB.

#### Scenario: Python file uploaded
- **WHEN** a .py file is uploaded
- **THEN** the full file content is parsed without truncation

### Requirement: File Download URL Field
The system SHALL include a download URL field in the parsed result JSON when sending data to the LLM.

#### Scenario: Parse result sent to LLM
- **WHEN** parsed file data is assembled into the system prompt
- **THEN** each file entry includes a download_url field

### Requirement: System Prompt Assembly
The system SHALL inject the parsed JSON as system prompt context into the LLM.
After injection, the file upload task SHALL be marked as complete.

#### Scenario: File ready for conversation
- **WHEN** a file is uploaded and parsed
- **THEN** the parsed JSON is included in the system prompt context for that conversation session
