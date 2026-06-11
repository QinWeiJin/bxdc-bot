package com.lobsterai.skillgateway.dto;

import java.util.List;
import java.util.Map;

/**
 * 文件解析结果 DTO。
 * <p>
 * 所有文件解析器（Word/Excel/CSV/TXT/MD/Py）的统一输出格式。
 * 序列化为 JSON 后注入系统提示词、存储到 UserFile.parsedSummary。
 * </p>
 */
public class FileParseResult {

    /** 文件类型：word / excel / csv / txt / md / py / pdf */
    private String fileType;

    /** 文件下载 URL */
    private String downloadUrl;

    /** 原始文件名 */
    private String originalFileName;

    /** 文件大小（字节） */
    private Long fileSize;

    // === Word/PDF 解析字段 ===
    /** 预估页数 */
    private Integer pageEstimate;
    /** 段落总数 */
    private Integer paragraphCount;
    /** 表格数量 */
    private Integer tableCount;
    /** 图片数量 */
    private Integer imageCount;
    /** 章节大纲 */
    private List<OutlineItem> outline;
    /** 表格信息 */
    private List<TableInfo> tables;
    /** 章节信息 */
    private List<SectionInfo> sections;
    /** 图片信息 */
    private List<ImageInfo> images;
    /** 是否有宏 */
    private Boolean hasMacro;
    /** 是否加密 */
    private Boolean isEncrypted;

    // === Excel/CSV 解析字段 ===
    /** Sheet 数量 */
    private Integer sheetCount;
    /** Sheet 详情 */
    private List<SheetInfo> sheets;

    // === CSV 解析字段 ===
    /** 检测到的分隔符 */
    private String delimiterDetected;
    /** 检测到的编码 */
    private String encodingDetected;

    // === TXT/MD/Py 解析字段 ===
    /** 总行数 */
    private Integer lineCount;
    /** 内容预览（前 500 字） */
    private String contentPreview;
    /** 全量内容（py 文件全量解析时使用） */
    private String fullContent;

    // ========== 内部类 ==========

    public static class OutlineItem {
        private Integer level;
        private String text;
        private Integer paragraphIndex;
        private Integer charCount;
        private List<OutlineItem> children;

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Integer getParagraphIndex() { return paragraphIndex; }
        public void setParagraphIndex(Integer paragraphIndex) { this.paragraphIndex = paragraphIndex; }
        public Integer getCharCount() { return charCount; }
        public void setCharCount(Integer charCount) { this.charCount = charCount; }
        public List<OutlineItem> getChildren() { return children; }
        public void setChildren(List<OutlineItem> children) { this.children = children; }
    }

    public static class TableInfo {
        private Integer tableIndex;
        private Integer paragraphIndex;
        private Integer rowCount;
        private Integer colCount;
        private Boolean hasHeader;
        private List<String> headerText;
        private String locationDescription;

        public Integer getTableIndex() { return tableIndex; }
        public void setTableIndex(Integer tableIndex) { this.tableIndex = tableIndex; }
        public Integer getParagraphIndex() { return paragraphIndex; }
        public void setParagraphIndex(Integer paragraphIndex) { this.paragraphIndex = paragraphIndex; }
        public Integer getRowCount() { return rowCount; }
        public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
        public Integer getColCount() { return colCount; }
        public void setColCount(Integer colCount) { this.colCount = colCount; }
        public Boolean getHasHeader() { return hasHeader; }
        public void setHasHeader(Boolean hasHeader) { this.hasHeader = hasHeader; }
        public List<String> getHeaderText() { return headerText; }
        public void setHeaderText(List<String> headerText) { this.headerText = headerText; }
        public String getLocationDescription() { return locationDescription; }
        public void setLocationDescription(String locationDescription) { this.locationDescription = locationDescription; }
    }

    public static class SectionInfo {
        private String heading;
        private Integer level;
        private Integer paragraphIndex;
        private Integer totalChars;
        private Boolean hasTables;
        private Boolean hasImages;

        public String getHeading() { return heading; }
        public void setHeading(String heading) { this.heading = heading; }
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }
        public Integer getParagraphIndex() { return paragraphIndex; }
        public void setParagraphIndex(Integer paragraphIndex) { this.paragraphIndex = paragraphIndex; }
        public Integer getTotalChars() { return totalChars; }
        public void setTotalChars(Integer totalChars) { this.totalChars = totalChars; }
        public Boolean getHasTables() { return hasTables; }
        public void setHasTables(Boolean hasTables) { this.hasTables = hasTables; }
        public Boolean getHasImages() { return hasImages; }
        public void setHasImages(Boolean hasImages) { this.hasImages = hasImages; }
    }

    public static class ImageInfo {
        private Integer imageIndex;
        private Integer paragraphIndex;
        private Integer widthPx;
        private Integer heightPx;

        public Integer getImageIndex() { return imageIndex; }
        public void setImageIndex(Integer imageIndex) { this.imageIndex = imageIndex; }
        public Integer getParagraphIndex() { return paragraphIndex; }
        public void setParagraphIndex(Integer paragraphIndex) { this.paragraphIndex = paragraphIndex; }
        public Integer getWidthPx() { return widthPx; }
        public void setWidthPx(Integer widthPx) { this.widthPx = widthPx; }
        public Integer getHeightPx() { return heightPx; }
        public void setHeightPx(Integer heightPx) { this.heightPx = heightPx; }
    }

    public static class SheetInfo {
        private String sheetName;
        private Integer sheetIndex;
        private Integer rowCount;
        private Integer colCount;
        private Boolean hasHeader;
        private Integer headerRowIndex;
        private List<ColumnInfo> columns;
        private Boolean hasFormula;
        private Boolean hasMergedCells;
        private List<String> mergedCellRanges;
        private Boolean hasChart;
        private Boolean hasPivotTable;
        private Integer dataRowCount;
        private String frozenPanes;

        public String getSheetName() { return sheetName; }
        public void setSheetName(String sheetName) { this.sheetName = sheetName; }
        public Integer getSheetIndex() { return sheetIndex; }
        public void setSheetIndex(Integer sheetIndex) { this.sheetIndex = sheetIndex; }
        public Integer getRowCount() { return rowCount; }
        public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
        public Integer getColCount() { return colCount; }
        public void setColCount(Integer colCount) { this.colCount = colCount; }
        public Boolean getHasHeader() { return hasHeader; }
        public void setHasHeader(Boolean hasHeader) { this.hasHeader = hasHeader; }
        public Integer getHeaderRowIndex() { return headerRowIndex; }
        public void setHeaderRowIndex(Integer headerRowIndex) { this.headerRowIndex = headerRowIndex; }
        public List<ColumnInfo> getColumns() { return columns; }
        public void setColumns(List<ColumnInfo> columns) { this.columns = columns; }
        public Boolean getHasFormula() { return hasFormula; }
        public void setHasFormula(Boolean hasFormula) { this.hasFormula = hasFormula; }
        public Boolean getHasMergedCells() { return hasMergedCells; }
        public void setHasMergedCells(Boolean hasMergedCells) { this.hasMergedCells = hasMergedCells; }
        public List<String> getMergedCellRanges() { return mergedCellRanges; }
        public void setMergedCellRanges(List<String> mergedCellRanges) { this.mergedCellRanges = mergedCellRanges; }
        public Boolean getHasChart() { return hasChart; }
        public void setHasChart(Boolean hasChart) { this.hasChart = hasChart; }
        public Boolean getHasPivotTable() { return hasPivotTable; }
        public void setHasPivotTable(Boolean hasPivotTable) { this.hasPivotTable = hasPivotTable; }
        public Integer getDataRowCount() { return dataRowCount; }
        public void setDataRowCount(Integer dataRowCount) { this.dataRowCount = dataRowCount; }
        public String getFrozenPanes() { return frozenPanes; }
        public void setFrozenPanes(String frozenPanes) { this.frozenPanes = frozenPanes; }
    }

    public static class ColumnInfo {
        private Integer colIndex;
        private String colLetter;
        private String headerName;
        private String inferredType;
        private Integer nullCount;
        private Integer uniqueCount;
        private List<String> sampleValues;
        private Map<String, Object> stats;
        private String minDate;
        private String maxDate;
        private List<String> categories;
        private Map<String, Integer> categoryCounts;

        // Getters and setters needed for Jackson (no Lombok)
        public Integer getColIndex() { return colIndex; }
        public void setColIndex(Integer colIndex) { this.colIndex = colIndex; }
        public String getColLetter() { return colLetter; }
        public void setColLetter(String colLetter) { this.colLetter = colLetter; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public String getInferredType() { return inferredType; }
        public void setInferredType(String inferredType) { this.inferredType = inferredType; }
        public Integer getNullCount() { return nullCount; }
        public void setNullCount(Integer nullCount) { this.nullCount = nullCount; }
        public Integer getUniqueCount() { return uniqueCount; }
        public void setUniqueCount(Integer uniqueCount) { this.uniqueCount = uniqueCount; }
        public List<String> getSampleValues() { return sampleValues; }
        public void setSampleValues(List<String> sampleValues) { this.sampleValues = sampleValues; }
        public Map<String, Object> getStats() { return stats; }
        public void setStats(Map<String, Object> stats) { this.stats = stats; }
        public String getMinDate() { return minDate; }
        public void setMinDate(String minDate) { this.minDate = minDate; }
        public String getMaxDate() { return maxDate; }
        public void setMaxDate(String maxDate) { this.maxDate = maxDate; }
        public List<String> getCategories() { return categories; }
        public void setCategories(List<String> categories) { this.categories = categories; }
        public Map<String, Integer> getCategoryCounts() { return categoryCounts; }
        public void setCategoryCounts(Map<String, Integer> categoryCounts) { this.categoryCounts = categoryCounts; }
    }

    // ========== Getters & Setters for top-level fields ==========

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getPageEstimate() { return pageEstimate; }
    public void setPageEstimate(Integer pageEstimate) { this.pageEstimate = pageEstimate; }
    public Integer getParagraphCount() { return paragraphCount; }
    public void setParagraphCount(Integer paragraphCount) { this.paragraphCount = paragraphCount; }
    public Integer getTableCount() { return tableCount; }
    public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
    public Integer getImageCount() { return imageCount; }
    public void setImageCount(Integer imageCount) { this.imageCount = imageCount; }
    public List<OutlineItem> getOutline() { return outline; }
    public void setOutline(List<OutlineItem> outline) { this.outline = outline; }
    public List<TableInfo> getTables() { return tables; }
    public void setTables(List<TableInfo> tables) { this.tables = tables; }
    public List<SectionInfo> getSections() { return sections; }
    public void setSections(List<SectionInfo> sections) { this.sections = sections; }
    public List<ImageInfo> getImages() { return images; }
    public void setImages(List<ImageInfo> images) { this.images = images; }
    public Boolean getHasMacro() { return hasMacro; }
    public void setHasMacro(Boolean hasMacro) { this.hasMacro = hasMacro; }
    public Boolean getIsEncrypted() { return isEncrypted; }
    public void setIsEncrypted(Boolean isEncrypted) { this.isEncrypted = isEncrypted; }
    public Integer getSheetCount() { return sheetCount; }
    public void setSheetCount(Integer sheetCount) { this.sheetCount = sheetCount; }
    public List<SheetInfo> getSheets() { return sheets; }
    public void setSheets(List<SheetInfo> sheets) { this.sheets = sheets; }
    public String getDelimiterDetected() { return delimiterDetected; }
    public void setDelimiterDetected(String delimiterDetected) { this.delimiterDetected = delimiterDetected; }
    public String getEncodingDetected() { return encodingDetected; }
    public void setEncodingDetected(String encodingDetected) { this.encodingDetected = encodingDetected; }
    public Integer getLineCount() { return lineCount; }
    public void setLineCount(Integer lineCount) { this.lineCount = lineCount; }
    public String getContentPreview() { return contentPreview; }
    public void setContentPreview(String contentPreview) { this.contentPreview = contentPreview; }
    public String getFullContent() { return fullContent; }
    public void setFullContent(String fullContent) { this.fullContent = fullContent; }
}
