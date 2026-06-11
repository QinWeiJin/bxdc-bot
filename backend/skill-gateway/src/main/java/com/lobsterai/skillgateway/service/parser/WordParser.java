package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Word 文档解析器。
 * <p>
 * 支持 .doc (HWPF) 和 .docx (XWPF) 格式。
 * 提取标题/章节层级、表格、图片，产出结构化 JSON。
 * 无标题时降级：大号字体 → 编号内容 → 前 500 字符。
 * </p>
 */
@Component
public class WordParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(WordParser.class);

    private static final Pattern NUMBERED_PATTERN = Pattern.compile(
            "^\\s*([0-9]+[\\.\\)、]|[\\(（][0-9]+[\\)）]|[第][0-9一二三四五六七八九十百]+[章节条款]|[IVX]+[\\.\\)、])"
    );

    private static final int CONTENT_PREVIEW_CHARS = 500;

    @Override
    public String supportedType() {
        return "docx"; // also handles "doc" via the router
    }

    @Override
    public FileParseResult parse(byte[] fileBytes, String fileName) throws Exception {
        String ext = extractExtension(fileName);
        if ("docx".equals(ext)) {
            return parseDocx(fileBytes, fileName);
        } else {
            return parseDoc(fileBytes, fileName);
        }
    }

    // ========== DOCX (XWPF) ==========

    private FileParseResult parseDocx(byte[] fileBytes, String fileName) throws Exception {
        FileParseResult result = new FileParseResult();
        result.setFileType("word");

        XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileBytes));
        try {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            result.setParagraphCount(paragraphs.size());

            // 提取大纲
            List<FileParseResult.OutlineItem> outline = extractDocxOutline(paragraphs);
            result.setOutline(outline);

            // 如果无标题，降级提取
            if (outline.isEmpty()) {
                String preview = extractDocxFallback(paragraphs);
                result.setContentPreview(preview);
            }

            // 提取表格
            List<XWPFTable> xwpfTables = doc.getTables();
            result.setTableCount(xwpfTables.size());
            List<FileParseResult.TableInfo> tables = new ArrayList<FileParseResult.TableInfo>();
            for (int i = 0; i < xwpfTables.size(); i++) {
                XWPFTable table = xwpfTables.get(i);
                FileParseResult.TableInfo ti = new FileParseResult.TableInfo();
                ti.setTableIndex(i);
                ti.setRowCount(table.getRows().size());
                if (!table.getRows().isEmpty()) {
                    XWPFTableRow row = table.getRows().get(0);
                    ti.setColCount(row.getTableCells().size());
                    List<String> headerText = new ArrayList<String>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        headerText.add(cell.getText());
                    }
                    ti.setHeaderText(headerText);
                    ti.setHasHeader(true);
                }
                tables.add(ti);
            }
            result.setTables(tables);

            // 提取图片
            List<FileParseResult.ImageInfo> images = new ArrayList<FileParseResult.ImageInfo>();
            result.setImages(images);
            result.setImageCount(images.size());

            result.setIsEncrypted(false);
            result.setHasMacro(false);

        } finally {
            doc.close();
        }
        return result;
    }

    // ========== DOC (HWPF) ==========

    private FileParseResult parseDoc(byte[] fileBytes, String fileName) throws Exception {
        FileParseResult result = new FileParseResult();
        result.setFileType("word");

        HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(fileBytes));
        try {
            Range range = doc.getRange();
            int paraCount = range.numParagraphs();
            result.setParagraphCount(paraCount);

            // 检测宏（.doc 文件检测）
            result.setHasMacro(false);

            // 提取大纲
            List<FileParseResult.OutlineItem> outline = extractDocOutline(doc, range);
            result.setOutline(outline);

            if (outline.isEmpty()) {
                String preview = extractDocFallback(range);
                result.setContentPreview(preview);
            }

            result.setTableCount(0);
            result.setTables(new ArrayList<FileParseResult.TableInfo>());
            result.setImages(new ArrayList<FileParseResult.ImageInfo>());
            result.setImageCount(0);
            result.setIsEncrypted(false);

        } finally {
            doc.close();
        }
        return result;
    }

    // ========== DOCX outline extraction ==========

    private List<FileParseResult.OutlineItem> extractDocxOutline(List<XWPFParagraph> paragraphs) {
        List<FileParseResult.OutlineItem> roots = new ArrayList<FileParseResult.OutlineItem>();
        Map<Integer, FileParseResult.OutlineItem> levelStack = new LinkedHashMap<Integer, FileParseResult.OutlineItem>();

        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph para = paragraphs.get(i);
            String style = para.getStyle();
            if (style == null) continue;

            Integer headingLevel = getHeadingLevel(style);
            if (headingLevel == null || headingLevel < 1 || headingLevel > 4) continue;

            String text = para.getText().trim();
            if (text.isEmpty()) continue;

            FileParseResult.OutlineItem item = new FileParseResult.OutlineItem();
            item.setLevel(headingLevel);
            item.setText(text);
            item.setParagraphIndex(i);
            item.setCharCount(text.length());

            // Clear deeper levels
            List<Integer> keysToRemove = new ArrayList<Integer>();
            for (Integer lv : levelStack.keySet()) {
                if (lv >= headingLevel) keysToRemove.add(lv);
            }
            for (Integer lv : keysToRemove) {
                levelStack.remove(lv);
            }

            FileParseResult.OutlineItem parent = null;
            for (Map.Entry<Integer, FileParseResult.OutlineItem> entry : levelStack.entrySet()) {
                parent = entry.getValue();
            }

            if (parent != null && headingLevel > 1) {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<FileParseResult.OutlineItem>());
                }
                parent.getChildren().add(item);
            } else {
                roots.add(item);
            }
            levelStack.put(headingLevel, item);
        }
        return roots;
    }

    // ========== DOC outline extraction ==========

    private List<FileParseResult.OutlineItem> extractDocOutline(HWPFDocument doc, Range range) {
        List<FileParseResult.OutlineItem> roots = new ArrayList<FileParseResult.OutlineItem>();
        Map<Integer, FileParseResult.OutlineItem> levelStack = new LinkedHashMap<Integer, FileParseResult.OutlineItem>();
        StyleSheet styleSheet = doc.getStyleSheet();
        int paraCount = range.numParagraphs();

        for (int i = 0; i < paraCount; i++) {
            Paragraph para = range.getParagraph(i);
            int styleIndex = para.getStyleIndex();
            if (styleIndex < 0) continue;

            StyleDescription styleDesc = styleSheet.getStyleDescription(styleIndex);
            if (styleDesc == null) continue;

            String styleName = styleDesc.getName();
            Integer headingLevel = getHeadingLevel(styleName);
            if (headingLevel == null) continue;

            String text = para.text().trim();
            if (text.isEmpty()) continue;

            FileParseResult.OutlineItem item = new FileParseResult.OutlineItem();
            item.setLevel(headingLevel);
            item.setText(text);
            item.setParagraphIndex(i);
            item.setCharCount(text.length());

            List<Integer> keysToRemove = new ArrayList<Integer>();
            for (Integer lv : levelStack.keySet()) {
                if (lv >= headingLevel) keysToRemove.add(lv);
            }
            for (Integer lv : keysToRemove) {
                levelStack.remove(lv);
            }

            FileParseResult.OutlineItem parent = null;
            for (Map.Entry<Integer, FileParseResult.OutlineItem> entry : levelStack.entrySet()) {
                parent = entry.getValue();
            }

            if (parent != null && headingLevel > 1) {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<FileParseResult.OutlineItem>());
                }
                parent.getChildren().add(item);
            } else {
                roots.add(item);
            }
            levelStack.put(headingLevel, item);
        }
        return roots;
    }

    // ========== Fallback extraction ==========

    private String extractDocxFallback(List<XWPFParagraph> paragraphs) {
        // Strategy 1: large font first paragraph
        Integer maxFontSize = null;
        String largeFontText = null;

        for (XWPFParagraph para : paragraphs) {
            List<XWPFRun> runs = para.getRuns();
            if (runs == null || runs.isEmpty()) continue;
            for (XWPFRun run : runs) {
                // getFontSize() returns Number in POI, check null
                Number rawSize = run.getFontSize();
                if (rawSize == null) continue;
                int fontSize = rawSize.intValue();
                if (fontSize > 0 && (maxFontSize == null || fontSize > maxFontSize)) {
                    maxFontSize = fontSize;
                    largeFontText = para.getText().trim();
                }
            }
            if (largeFontText != null && !largeFontText.isEmpty() && maxFontSize != null && maxFontSize > 12) {
                break;
            }
        }

        if (largeFontText != null && !largeFontText.isEmpty() && maxFontSize != null && maxFontSize > 12) {
            return truncateText(largeFontText, CONTENT_PREVIEW_CHARS);
        }

        // Strategy 2: numbered paragraph
        for (XWPFParagraph para : paragraphs) {
            String text = para.getText().trim();
            if (NUMBERED_PATTERN.matcher(text).find()) {
                return truncateText(text, CONTENT_PREVIEW_CHARS);
            }
        }

        // Strategy 3: first 500 chars
        return extractFirstChars(paragraphs, CONTENT_PREVIEW_CHARS);
    }

    private String extractDocFallback(Range range) {
        int paraCount = range.numParagraphs();
        for (int i = 0; i < paraCount && i < 20; i++) {
            Paragraph para = range.getParagraph(i);
            String text = para.text().trim();
            if (!text.isEmpty()) {
                if (NUMBERED_PATTERN.matcher(text).find()) {
                    return truncateText(text, CONTENT_PREVIEW_CHARS);
                }
            }
        }
        // First 500 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paraCount && sb.length() < CONTENT_PREVIEW_CHARS; i++) {
            Paragraph para = range.getParagraph(i);
            String text = para.text().trim();
            if (!text.isEmpty()) {
                sb.append(text).append("\n");
            }
        }
        return truncateText(sb.toString(), CONTENT_PREVIEW_CHARS);
    }

    // ========== Utilities ==========

    private String extractFirstChars(List<XWPFParagraph> paragraphs, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph para : paragraphs) {
            String text = para.getText().trim();
            if (!text.isEmpty()) {
                sb.append(text).append("\n");
                if (sb.length() >= maxChars) break;
            }
        }
        return truncateText(sb.toString(), maxChars);
    }

    private String truncateText(String text, int maxChars) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(0, maxChars);
    }

    /**
     * 从样式名称推断标题级别。
     */
    static Integer getHeadingLevel(String styleName) {
        if (styleName == null) return null;
        String s = styleName.toLowerCase().trim();
        if (s.contains("heading") || s.contains("标题")) {
            for (int i = 1; i <= 9; i++) {
                if (s.contains(String.valueOf(i))) return i;
            }
            return 1; // "Heading" without number → level 1
        }
        if (s.contains("title") || s.equals("toc") || s.equals("toc1")) {
            return 1;
        }
        return null;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
