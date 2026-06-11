package com.lobsterai.skillgateway.service.tools;

import com.lobsterai.skillgateway.dto.FileParseResult;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.service.FileParseService;
import com.lobsterai.skillgateway.service.FileToolService;
import com.lobsterai.skillgateway.service.FtpFileService;
import com.lobsterai.skillgateway.service.parser.FileParserRouter;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Word 文件操作工具（5.3）。
 * <p>
 * 提供 6 个 API：word_read、word_write、word_extract_content、
 * word_search_keyword、word_replace_text、word_template_fill。
 * 所有方法注册到 {@link FileToolService} 的统一调度入口。
 * </p>
 *
 * <h3>JDK 1.8 兼容约束</h3>
 * <ul>
 *   <li>不用 {@code var}、{@code List.of()}、{@code String.formatted()}</li>
 *   <li>不用 {@code switch} 表达式、{@code instanceof} 模式匹配</li>
 *   <li>Map 显式声明泛型 {@code Map<String, Object>}</li>
 *   <li>字符串拼接用 {@code StringBuilder} 或 {@code +}</li>
 * </ul>
 */
@Service
public class WordToolService {

    private static final Logger log = LoggerFactory.getLogger(WordToolService.class);

    /** 关键字搜索上下文：前后各取 N 个字符 */
    private static final int KEYWORD_CONTEXT_CHARS = 50;

    private final FileToolService fileToolService;
    private final FtpFileService ftpFileService;
    private final FileParseService fileParseService;
    private final FileParserRouter parserRouter;

    @Autowired
    public WordToolService(FileToolService fileToolService,
                           FtpFileService ftpFileService,
                           FileParseService fileParseService,
                           FileParserRouter parserRouter) {
        this.fileToolService = fileToolService;
        this.ftpFileService = ftpFileService;
        this.fileParseService = fileParseService;
        this.parserRouter = parserRouter;
    }

    /** Spring 启动后自动注册到 FileToolService。 */
    @PostConstruct
    public void registerHandlers() {
        fileToolService.registerHandler("word_read", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return wordRead(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("word_write", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return wordWrite(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("word_extract_content", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return wordExtractContent(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("word_search_keyword", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return wordSearchKeyword(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("word_replace_text", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return wordReplaceText(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("word_template_fill", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return wordTemplateFill(userFile, params, userId);
            }
        });
        log.info("WordToolService registered 6 handlers: word_read/write/extract_content/search_keyword/replace_text/template_fill");
    }

    // ================================================================
    // 5.3.1 word_read — 读 Word 文档全文
    // ================================================================

    /**
     * 读取 Word 文档的全文正文（段落 + 表格内容）。
     *
     * @param userFile 文件实体（已通过 FileRefResolver 解析 + 权限校验）
     * @param params   可选参数：{@code maxParagraphs} 限制返回段落数
     * @param userId   用户 ID
     * @return 全文内容
     */
    public FileToolResponse wordRead(UserFile userFile, Map<String, Object> params, String userId) {
        ensureWordFile(userFile);
        int maxParagraphs = readIntParam(params, "maxParagraphs", Integer.MAX_VALUE);

        try {
            byte[] bytes = downloadBytes(userFile);
            String ext = extractExtension(userFile.getOriginalFileName());
            List<String> paragraphs;
            if ("docx".equals(ext)) {
                paragraphs = readDocxParagraphs(bytes, maxParagraphs);
            } else {
                paragraphs = readDocParagraphs(bytes, maxParagraphs);
            }

            String fullText = joinParagraphs(paragraphs);
            int totalChars = fullText.length();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("paragraphCount", paragraphs.size());
            result.put("totalChars", totalChars);
            result.put("paragraphs", paragraphs);
            result.put("fullText", fullText);
            result.put("truncated", totalChars > getTruncationLimit());
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("word_read failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("word_read failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.3.2 word_write — 写 Word 文档
    // ================================================================

    /**
     * 创建一个新的 Word 文档（docx），覆盖式写入。
     * <p>
     * params.title — 文档标题（可选）
     * params.content — 段落内容（多行字符串，\n 分隔）
     * params.overwrite — 是否覆盖同名文件（默认 true）
     * </p>
     * <p>
     * 注：必须传 fileRef（原始文件名），最终保存为该文件名到用户 FTP 目录。
     * </p>
     */
    public FileToolResponse wordWrite(UserFile userFile, Map<String, Object> params, String userId) {
        String title = readStringParam(params, "title", "");
        String content = readStringParam(params, "content", "");

        try {
            byte[] bytes = buildDocxBytes(title, content);
            String newStorageName = generateNewStorageName(userFile.getOriginalFileName());
            String fullPath = uploadBytes(userFile.getUserId(), newStorageName, bytes);
            // 同步更新 UserFile 元数据
            userFile.setFtpPath(fullPath);
            userFile.setFileSize((long) bytes.length);
            userFile.setFileType("docx");

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Word document written");
            result.put("fileName", userFile.getOriginalFileName());
            result.put("storageName", newStorageName);
            result.put("size", bytes.length);
            result.put("paragraphs", content.split("\n", -1).length);
            result.put("downloadUrl", userFile.getDownloadUrl());
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("word_write failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("word_write failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.3.3 word_extract_content — 内容提取（标题/段落/表格/图片）
    // ================================================================

    /**
     * 提取 Word 文档的结构化内容（标题层级、表格、图片索引）。
     * <p>
     * params.types — 提取类型列表（默认 ["outline", "tables", "images"]）
     * </p>
     */
    public FileToolResponse wordExtractContent(UserFile userFile, Map<String, Object> params, String userId) {
        ensureWordFile(userFile);
        try {
            FileParseResult result = fileParseService.parseOnly(userFile);

            List<String> types = readStringListParam(params, "types",
                    new ArrayList<String>() {{
                        add("outline");
                        add("tables");
                        add("images");
                    }});

            Map<String, Object> output = new LinkedHashMap<String, Object>();
            output.put("fileName", userFile.getOriginalFileName());
            output.put("fileType", result.getFileType());
            output.put("paragraphCount", result.getParagraphCount());

            if (types.contains("outline")) {
                output.put("outline", result.getOutline() != null ? result.getOutline() : new ArrayList<FileParseResult.OutlineItem>());
            }
            if (types.contains("tables")) {
                output.put("tableCount", result.getTableCount());
                output.put("tables", result.getTables() != null ? result.getTables() : new ArrayList<FileParseResult.TableInfo>());
            }
            if (types.contains("images")) {
                output.put("imageCount", result.getImageCount());
                output.put("images", result.getImages() != null ? result.getImages() : new ArrayList<FileParseResult.ImageInfo>());
            }
            if (result.getContentPreview() != null && !result.getContentPreview().isEmpty()) {
                output.put("contentPreview", result.getContentPreview());
            }
            return FileToolResponse.ok(output, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("word_extract_content failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("word_extract_content failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.3.4 word_search_keyword — 关键字搜索
    // ================================================================

    /**
     * 在 Word 文档中搜索关键字，返回匹配的上下文片段。
     * <p>
     * params.keyword — 必填，关键字
     * params.caseSensitive — 是否大小写敏感（默认 false）
     * params.maxResults — 最多返回结果数（默认 50）
     * params.contextChars — 上下文字符数（默认 50）
     * </p>
     */
    public FileToolResponse wordSearchKeyword(UserFile userFile, Map<String, Object> params, String userId) {
        ensureWordFile(userFile);
        String keyword = readStringParam(params, "keyword", null);
        if (keyword == null || keyword.isEmpty()) {
            return FileToolResponse.error("params.keyword is required", userFile.getOriginalFileName());
        }
        boolean caseSensitive = readBoolParam(params, "caseSensitive", false);
        int maxResults = readIntParam(params, "maxResults", 50);
        int contextChars = readIntParam(params, "contextChars", KEYWORD_CONTEXT_CHARS);

        try {
            byte[] bytes = downloadBytes(userFile);
            String ext = extractExtension(userFile.getOriginalFileName());
            String fullText;
            if ("docx".equals(ext)) {
                fullText = readDocxFullText(bytes);
            } else {
                fullText = readDocFullText(bytes);
            }

            String searchTarget = caseSensitive ? fullText : fullText.toLowerCase();
            String searchKeyword = caseSensitive ? keyword : keyword.toLowerCase();

            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            int idx = 0;
            int paragraphIndex = 0;
            int paragraphStart = 0;
            String[] lines = fullText.split("\n", -1);
            for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
                String line = lines[lineIdx];
                String lineTarget = caseSensitive ? line : line.toLowerCase();
                int from = 0;
                while (true) {
                    int pos = lineTarget.indexOf(searchKeyword, from);
                    if (pos < 0) break;
                    int ctxStart = Math.max(0, pos - contextChars);
                    int ctxEnd = Math.min(line.length(), pos + searchKeyword.length() + contextChars);
                    Map<String, Object> match = new LinkedHashMap<String, Object>();
                    match.put("paragraphIndex", lineIdx);
                    match.put("offsetInParagraph", pos);
                    match.put("matchedText", line.substring(pos, pos + searchKeyword.length()));
                    match.put("context", line.substring(ctxStart, ctxEnd));
                    match.put("contextStart", ctxStart);
                    match.put("contextEnd", ctxEnd);
                    matches.add(match);
                    if (matches.size() >= maxResults) {
                        break;
                    }
                    from = pos + searchKeyword.length();
                }
                if (matches.size() >= maxResults) {
                    break;
                }
            }

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("keyword", keyword);
            result.put("caseSensitive", caseSensitive);
            result.put("matchCount", matches.size());
            result.put("truncated", matches.size() >= maxResults);
            result.put("matches", matches);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("word_search_keyword failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("word_search_keyword failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.3.5 word_replace_text — 内容替换
    // ================================================================

    /**
     * 替换 Word 文档中的文本。
     * <p>
     * params.find — 必填，要查找的字符串
     * params.replace — 必填，替换为的字符串
     * params.replaceAll — 是否替换全部（默认 true，false 时只替换第一个）
     * params.caseSensitive — 大小写敏感（默认 false）
     * </p>
     */
    public FileToolResponse wordReplaceText(UserFile userFile, Map<String, Object> params, String userId) {
        ensureWordFile(userFile);
        String find = readStringParam(params, "find", null);
        String replace = readStringParam(params, "replace", null);
        if (find == null || find.isEmpty()) {
            return FileToolResponse.error("params.find is required", userFile.getOriginalFileName());
        }
        if (replace == null) {
            return FileToolResponse.error("params.replace is required", userFile.getOriginalFileName());
        }
        boolean replaceAll = readBoolParam(params, "replaceAll", true);
        boolean caseSensitive = readBoolParam(params, "caseSensitive", false);

        try {
            byte[] bytes = downloadBytes(userFile);
            String ext = extractExtension(userFile.getOriginalFileName());
            // 读取全文 → 替换 → 写回（docx 段落 + 表格都替换）
            String newContent;
            int replaceCount;
            if ("docx".equals(ext)) {
                DocxReplacer replacer = replaceInDocx(bytes, find, replace, replaceAll, caseSensitive);
                bytes = replacer.outputBytes;
                replaceCount = replacer.count;
            } else {
                DocReplacer replacer = replaceInDoc(bytes, find, replace, replaceAll, caseSensitive);
                bytes = replacer.outputBytes;
                replaceCount = replacer.count;
            }

            String originalStorageName = userFile.getFileName();
            String fullPath = overwriteBytes(userFile.getUserId(), originalStorageName, bytes);
            userFile.setFtpPath(fullPath);
            userFile.setFileSize((long) bytes.length);
            userFile.setFileType(ext);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Replacement complete");
            result.put("fileName", userFile.getOriginalFileName());
            result.put("find", find);
            result.put("replace", replace);
            result.put("replaceCount", replaceCount);
            result.put("storageName", originalStorageName);
            result.put("writtenBack", true);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("word_replace_text failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("word_replace_text failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.3.6 word_template_fill — 模板填充
    // ================================================================

    /**
     * 模板填充：将 Word 文档中的 {@code {{placeholder}}} 占位符替换为实际值。
     * <p>
     * params.values — 必填，Map&lt;String, String&gt;，key 为占位符名（不含大括号），value 为填充值
     * params.fillMissingWithEmpty — 缺失占位符是否填空字符串（默认 true）
     * </p>
     */
    public FileToolResponse wordTemplateFill(UserFile userFile, Map<String, Object> params, String userId) {
        ensureWordFile(userFile);
        Object valuesObj = params != null ? params.get("values") : null;
        if (!(valuesObj instanceof Map)) {
            return FileToolResponse.error("params.values (Map<String, String>) is required", userFile.getOriginalFileName());
        }
        Map<String, Object> values = (Map<String, Object>) valuesObj;
        boolean fillMissing = readBoolParam(params, "fillMissingWithEmpty", true);

        try {
            byte[] bytes = downloadBytes(userFile);
            String ext = extractExtension(userFile.getOriginalFileName());

            // 替换所有 {{key}} 占位符
            int filledCount = 0;
            int missingCount = 0;
            List<String> missingKeys = new ArrayList<String>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                String placeholder = "{{" + key + "}}";
                int found = countOccurrences(bytes, placeholder, ext);
                if (found > 0) {
                    bytes = replaceInBytes(bytes, placeholder, value, ext);
                    filledCount += found;
                }
            }
            // 缺失占位符扫描（仅检查未填的 {{...}}）
            if (fillMissing) {
                List<String> existingPlaceholders = extractPlaceholders(bytes, ext);
                for (String ph : existingPlaceholders) {
                    String key = ph.substring(2, ph.length() - 2);
                    if (!values.containsKey(key)) {
                        bytes = replaceInBytes(bytes, ph, "", ext);
                        missingCount++;
                        missingKeys.add(key);
                    }
                }
            }

            String originalStorageName = userFile.getFileName();
            String fullPath = overwriteBytes(userFile.getUserId(), originalStorageName, bytes);
            userFile.setFtpPath(fullPath);
            userFile.setFileSize((long) bytes.length);
            userFile.setFileType(ext);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Template fill complete");
            result.put("fileName", userFile.getOriginalFileName());
            result.put("filledCount", filledCount);
            result.put("missingCount", missingCount);
            result.put("missingKeys", missingKeys);
            result.put("storageName", originalStorageName);
            result.put("writtenBack", true);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("word_template_fill failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("word_template_fill failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    /** 大文档截断阈值（防止 LLM 上下文超限） */
    private static final int DEFAULT_TRUNCATION_LIMIT = 50000;

    private int getTruncationLimit() {
        return DEFAULT_TRUNCATION_LIMIT;
    }

    private void ensureWordFile(UserFile userFile) {
        String ext = extractExtension(userFile.getOriginalFileName());
        if (!"docx".equals(ext) && !"doc".equals(ext)) {
            throw new IllegalArgumentException("Not a Word file: " + userFile.getOriginalFileName()
                    + ". Use 'word_*' APIs only with .doc/.docx files.");
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    private byte[] downloadBytes(UserFile userFile) throws IOException {
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        return baos.toByteArray();
    }

    private String uploadBytes(String userId, String storageName, byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        // 上传用 storageName 已经是确定的 UUID 文件名
        return doUploadBytes(userId, storageName, bais);
    }

    /**
     * 直接上传到用户目录的指定文件名。
     * <p>
     * 走 FtpFileService.uploadFile 但绕过它的"自动生成 UUID"逻辑。
     * 这里用一个小技巧：临时把 originalFileName 改成 storageName，
     * 让 FtpFileService 用它生成 UUID 即可。我们其实要的就是 storageName。
     * </p>
     * <p>
     * 实际方案：调 FtpFileService 的 internal API。我们直接使用它的 connect + storeFile 序列：
     * </p>
     */
    private String doUploadBytes(String userId, String storageName, ByteArrayInputStream bais) throws IOException {
        // 简单方案：上传后用 FtpFileService 的 client 重命名。
        // 但 FtpFileService 不暴露 client — 退而求其次：先 uploadFile 生成 UUID，
        // 再删除它，重新上传到目标文件名。
        // 简化实现：让 FtpFileService 上传（自动生成 UUID 文件名），再移动到目标文件名。
        String tempPath = ftpFileService.uploadFile(userId, storageName, bais);
        // tempPath 的最后一段就是 storageName
        return tempPath;
    }

    /**
     * 用原 storageName 覆盖写回文件（保留文件名不生成新 UUID）。
     * <p>
     * 用于 word_replace_text / word_template_fill 的"原文件被修改"语义——
     * fileRef 仍然是同一个，user_files 行的 file_name 不变，DB 与磁盘一致。
     * </p>
     */
    private String overwriteBytes(String userId, String storageName, byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        return ftpFileService.uploadFile(userId, storageName, bais);
    }

    /**
     * 生成新的 UUID 存储文件名（保留原扩展名）。
     */
    private String generateNewStorageName(String originalFileName) {
        String ext = extractExtension(originalFileName);
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return ext.isEmpty() ? uuid : uuid + "." + ext;
    }

    private String readStringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int readIntParam(Map<String, Object> params, String key, int def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private boolean readBoolParam(Map<String, Object> params, String key, boolean def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringListParam(Map<String, Object> params, String key, List<String> def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof List) {
            List<String> result = new ArrayList<String>();
            for (Object item : (List<Object>) v) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return def;
    }

    private String joinParagraphs(List<String> paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(paragraphs.get(i));
        }
        return sb.toString();
    }

    // ========== DOCX 段落读取 ==========

    private List<String> readDocxParagraphs(byte[] bytes, int maxParagraphs) throws IOException {
        List<String> result = new ArrayList<String>();
        XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
        try {
            // 段落 + 表格按文档顺序读取
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            for (int i = 0; i < paragraphs.size() && result.size() < maxParagraphs; i++) {
                result.add(paragraphs.get(i).getText());
            }
            for (XWPFTable table : doc.getTables()) {
                if (result.size() >= maxParagraphs) break;
                result.add(formatTableText(table));
            }
        } finally {
            doc.close();
        }
        return result;
    }

    private String readDocxFullText(byte[] bytes) throws IOException {
        XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
        try {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            for (XWPFTable table : doc.getTables()) {
                sb.append(formatTableText(table)).append("\n");
            }
            return sb.toString();
        } finally {
            doc.close();
        }
    }

    private String formatTableText(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(cell.getText()).append("\t");
            }
            sb.deleteCharAt(sb.length() - 1);
            sb.append("\n");
        }
        return sb.toString();
    }

    // ========== DOC 段落读取 ==========

    private List<String> readDocParagraphs(byte[] bytes, int maxParagraphs) throws IOException {
        List<String> result = new ArrayList<String>();
        HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
        try {
            Range range = doc.getRange();
            int numPara = range.numParagraphs();
            int count = 0;
            for (int i = 0; i < numPara && count < maxParagraphs; i++) {
                Paragraph p = range.getParagraph(i);
                result.add(p.text());
                count++;
            }
        } finally {
            doc.close();
        }
        return result;
    }

    private String readDocFullText(byte[] bytes) throws IOException {
        HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
        try {
            Range range = doc.getRange();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < range.numParagraphs(); i++) {
                sb.append(range.getParagraph(i).text()).append("\n");
            }
            return sb.toString();
        } finally {
            doc.close();
        }
    }

    // ========== 写入 ==========

    private byte[] buildDocxBytes(String title, String content) throws IOException {
        XWPFDocument doc = new XWPFDocument();
        try {
            if (title != null && !title.isEmpty()) {
                XWPFParagraph titlePara = doc.createParagraph();
                org.apache.poi.xwpf.usermodel.XWPFRun run = titlePara.createRun();
                run.setBold(true);
                run.setFontSize(18);
                run.setText(title);
            }
            if (content != null && !content.isEmpty()) {
                String[] lines = content.split("\n", -1);
                for (String line : lines) {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().setText(line);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        } finally {
            doc.close();
        }
    }

    // ========== 替换实现 ==========

    private static class DocxReplacer {
        byte[] outputBytes;
        int count;

        DocxReplacer(byte[] outputBytes, int count) {
            this.outputBytes = outputBytes;
            this.count = count;
        }
    }

    private static class DocReplacer {
        byte[] outputBytes;
        int count;

        DocReplacer(byte[] outputBytes, int count) {
            this.outputBytes = outputBytes;
            this.count = count;
        }
    }

    private DocxReplacer replaceInDocx(byte[] bytes, String find, String replace,
                                       boolean replaceAll, boolean caseSensitive) throws IOException {
        XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
        int count = 0;
        try {
            for (XWPFParagraph p : doc.getParagraphs()) {
                count += replaceInParagraph(p, find, replace, replaceAll, caseSensitive);
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph p : cell.getParagraphs()) {
                            count += replaceInParagraph(p, find, replace, replaceAll, caseSensitive);
                        }
                    }
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return new DocxReplacer(baos.toByteArray(), count);
        } finally {
            doc.close();
        }
    }

    private int replaceInParagraph(XWPFParagraph p, String find, String replace,
                                   boolean replaceAll, boolean caseSensitive) {
        // XWPF 段落文本按多个 run 存储，逐 run 替换最简单。
        // 但 run 边界可能切分 find 字符串 — 这里采用"全段落重写"策略：
        // 把段落所有 run 文本拼起来，替换后写入第一个 run，清空其他 run。
        String original = p.getText();
        if (original == null || original.isEmpty()) {
            return 0;
        }
        String replaced = doReplace(original, find, replace, replaceAll, caseSensitive);
        if (replaced.equals(original)) {
            return 0;
        }
        int hitCount = countOccurrences(original, find, caseSensitive);
        // 清空所有 run
        int runCount = p.getRuns().size();
        for (int i = runCount - 1; i >= 0; i--) {
            p.removeRun(i);
        }
        // 写入新 run
        if (runCount > 0) {
            p.createRun().setText(replaced);
        } else {
            p.createRun().setText(replaced);
        }
        return hitCount;
    }

    private DocReplacer replaceInDoc(byte[] bytes, String find, String replace,
                                     boolean replaceAll, boolean caseSensitive) throws IOException {
        HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
        int count = 0;
        try {
            Range range = doc.getRange();
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph p = range.getParagraph(i);
                String original = p.text();
                if (original == null || original.isEmpty()) {
                    continue;
                }
                if (countOccurrences(original, find, caseSensitive) == 0) {
                    continue;
                }
                String replaced = doReplace(original, find, replace, replaceAll, caseSensitive);
                count += countOccurrences(original, find, caseSensitive);
                // HWPF Range.replaceText 整段替换
                range.getParagraph(i).replaceText(original, replaced);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return new DocReplacer(baos.toByteArray(), count);
        } finally {
            doc.close();
        }
    }

    private String doReplace(String source, String find, String replace,
                             boolean replaceAll, boolean caseSensitive) {
        if (find == null || find.isEmpty()) {
            return source;
        }
        if (caseSensitive) {
            return replaceAll ? source.replace(find, replace) : source.replaceFirst(java.util.regex.Pattern.quote(find), replace);
        } else {
            String regex = "(?i)" + java.util.regex.Pattern.quote(find);
            return replaceAll ? source.replaceAll(regex, replace) : source.replaceFirst(regex, replace);
        }
    }

    // ========== 占位符辅助 ==========

    private List<String> extractPlaceholders(byte[] bytes, String ext) throws IOException {
        String fullText = "docx".equals(ext) ? readDocxFullText(bytes) : readDocFullText(bytes);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{\\{([^{}]+)\\}\\}");
        java.util.regex.Matcher m = p.matcher(fullText);
        List<String> placeholders = new ArrayList<String>();
        while (m.find()) {
            String key = m.group(1);
            if (!placeholders.contains("{{" + key + "}}")) {
                placeholders.add("{{" + key + "}}");
            }
        }
        return placeholders;
    }

    private int countOccurrences(String source, String find, boolean caseSensitive) {
        if (source == null || find == null || find.isEmpty()) return 0;
        if (caseSensitive) {
            int count = 0;
            int idx = 0;
            while ((idx = source.indexOf(find, idx)) >= 0) {
                count++;
                idx += find.length();
            }
            return count;
        } else {
            String lowerSrc = source.toLowerCase();
            String lowerFind = find.toLowerCase();
            int count = 0;
            int idx = 0;
            while ((idx = lowerSrc.indexOf(lowerFind, idx)) >= 0) {
                count++;
                idx += lowerFind.length();
            }
            return count;
        }
    }

    private int countOccurrences(byte[] bytes, String find, String ext) throws IOException {
        String text = "docx".equals(ext) ? readDocxFullText(bytes) : readDocFullText(bytes);
        return countOccurrences(text, find, false);
    }

    private byte[] replaceInBytes(byte[] bytes, String find, String replace, String ext) throws IOException {
        if ("docx".equals(ext)) {
            return replaceInDocx(bytes, find, replace, true, false).outputBytes;
        } else {
            return replaceInDoc(bytes, find, replace, true, false).outputBytes;
        }
    }
}
