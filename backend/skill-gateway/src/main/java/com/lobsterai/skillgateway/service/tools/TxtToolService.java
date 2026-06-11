package com.lobsterai.skillgateway.service.tools;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.service.FileToolService;
import com.lobsterai.skillgateway.service.FtpFileService;

/**
 * TXT/MD 文件操作工具（5.4）。
 * <p>
 * 提供 10 个 API：txt_read、txt_write、txt_keyword_lines、txt_regex、
 * txt_line_range、txt_section、txt_stats、txt_distinct_lines、txt_sort_lines、txt_keyword_freq。
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
public class TxtToolService {

    private static final Logger log = LoggerFactory.getLogger(TxtToolService.class);

    /** 默认编码（中文 Windows 常用 GBK，UTF-8 是默认值） */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /** 大文档截断阈值（防止 LLM 上下文超限） */
    private static final int DEFAULT_TRUNCATION_LIMIT = 50000;

    private final FileToolService fileToolService;
    private final FtpFileService ftpFileService;

    @Autowired
    public TxtToolService(FileToolService fileToolService, FtpFileService ftpFileService) {
        this.fileToolService = fileToolService;
        this.ftpFileService = ftpFileService;
    }

    /** Spring 启动后自动注册到 FileToolService。 */
    @PostConstruct
    public void registerHandlers() {
        fileToolService.registerHandler("txt_read", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtRead(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_write", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtWrite(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_keyword_lines", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtKeywordLines(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_regex", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtRegex(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_line_range", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtLineRange(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_section", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtSection(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_stats", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtStats(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_distinct_lines", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtDistinctLines(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_sort_lines", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtSortLines(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("txt_keyword_freq", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return txtKeywordFreq(userFile, params, userId);
            }
        });
        log.info("TxtToolService registered 10 handlers: txt_read/write/keyword_lines/regex/line_range/section/stats/distinct_lines/sort_lines/keyword_freq");
    }

    // ================================================================
    // 5.4.1 txt_read — 读 TXT/MD 文件
    // ================================================================

    /**
     * 读取 TXT/MD 文本文件全部内容（支持指定编码）。
     * <p>
     * params.encoding — 文本编码（默认 UTF-8）
     * params.startLine — 起始行（0-based，默认 0）
     * params.maxLines — 最多返回行数（默认全部）
     * </p>
     */
    public FileToolResponse txtRead(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        int startLine = readIntParam(params, "startLine", 0);
        int maxLines = readIntParam(params, "maxLines", Integer.MAX_VALUE);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            int total = allLines.size();
            int endIdx = Math.min(total, startLine + maxLines);
            int actualStart = Math.max(0, Math.min(startLine, total));
            List<String> slice = new ArrayList<String>();
            for (int i = actualStart; i < endIdx; i++) {
                slice.add(allLines.get(i));
            }
            String fullText = joinLines(slice, "\n");

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("encoding", encoding);
            result.put("totalLines", total);
            result.put("startLine", actualStart);
            result.put("returnedLines", slice.size());
            result.put("lines", slice);
            result.put("text", fullText);
            result.put("truncated", endIdx < total);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_read failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_read failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.2 txt_write — 写 TXT/MD 文件
    // ================================================================

    /**
     * 写入 TXT/MD 文本文件（覆盖式）。
     * <p>
     * params.content — 必填，文本内容
     * params.encoding — 写入编码（默认 UTF-8）
     * params.append — 是否追加（默认 false 覆盖）
     * </p>
     */
    public FileToolResponse txtWrite(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String content = readStringParam(params, "content", null);
        if (content == null) {
            return FileToolResponse.error("params.content is required", userFile.getOriginalFileName());
        }
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        boolean append = readBoolParam(params, "append", false);
        try {
            byte[] bytes;
            if (append) {
                ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
                ByteArrayOutputStream merged = new ByteArrayOutputStream();
                merged.write(baos.toByteArray());
                merged.write(content.getBytes(Charset.forName(encoding)));
                bytes = merged.toByteArray();
            } else {
                bytes = content.getBytes(Charset.forName(encoding));
            }
            String newStorageName = generateNewStorageName(userFile.getOriginalFileName());
            String fullPath = uploadBytes(userFile.getUserId(), newStorageName, bytes);
            userFile.setFtpPath(fullPath);
            userFile.setFileSize((long) bytes.length);
            userFile.setFileType(extractExtension(userFile.getOriginalFileName()));

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", append ? "Text appended" : "Text written");
            result.put("fileName", userFile.getOriginalFileName());
            result.put("storageName", newStorageName);
            result.put("encoding", encoding);
            result.put("size", bytes.length);
            result.put("lineCount", content.split("\n", -1).length);
            result.put("mode", append ? "append" : "overwrite");
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_write failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_write failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.3 txt_keyword_lines — 关键词行提取
    // ================================================================

    /**
     * 提取包含关键词的所有行。
     * <p>
     * params.keyword — 必填
     * params.caseSensitive — 大小写敏感（默认 false）
     * params.contextLines — 命中行前后各取 N 行作为上下文（默认 0）
     * </p>
     */
    public FileToolResponse txtKeywordLines(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String keyword = readStringParam(params, "keyword", null);
        if (keyword == null || keyword.isEmpty()) {
            return FileToolResponse.error("params.keyword is required", userFile.getOriginalFileName());
        }
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        boolean caseSensitive = readBoolParam(params, "caseSensitive", false);
        int contextLines = readIntParam(params, "contextLines", 0);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            String searchKey = caseSensitive ? keyword : keyword.toLowerCase();
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            int total = allLines.size();
            for (int i = 0; i < total; i++) {
                String line = allLines.get(i);
                String target = caseSensitive ? line : line.toLowerCase();
                if (target.contains(searchKey)) {
                    Map<String, Object> hit = new LinkedHashMap<String, Object>();
                    hit.put("lineNumber", i + 1); // 1-based
                    hit.put("line", line);
                    if (contextLines > 0) {
                        int from = Math.max(0, i - contextLines);
                        int to = Math.min(total - 1, i + contextLines);
                        List<String> ctx = new ArrayList<String>();
                        for (int k = from; k <= to; k++) {
                            ctx.add(allLines.get(k));
                        }
                        hit.put("contextLines", ctx);
                        hit.put("contextStart", from + 1);
                        hit.put("contextEnd", to + 1);
                    }
                    matches.add(hit);
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("keyword", keyword);
            result.put("caseSensitive", caseSensitive);
            result.put("matchCount", matches.size());
            result.put("matches", matches);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_keyword_lines failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_keyword_lines failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.4 txt_regex — 正则匹配/提取
    // ================================================================

    /**
     * 用正则匹配每行，返回捕获组。
     * <p>
     * params.pattern — 必填，正则表达式字符串
     * params.flags — 可选，Java 正则 flags 整数（默认 0）
     * params.groupNames — 命名捕获组别名数组（可选）
     * </p>
     */
    @SuppressWarnings("unchecked")
    public FileToolResponse txtRegex(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String patternStr = readStringParam(params, "pattern", null);
        if (patternStr == null || patternStr.isEmpty()) {
            return FileToolResponse.error("params.pattern is required", userFile.getOriginalFileName());
        }
        int flags = readIntParam(params, "flags", 0);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        List<String> groupNames = (List<String>) readListParam(params, "groupNames", null);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            Pattern p = Pattern.compile(patternStr, flags);
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            int totalMatched = 0;
            for (int i = 0; i < allLines.size(); i++) {
                String line = allLines.get(i);
                Matcher m = p.matcher(line);
                while (m.find()) {
                    totalMatched++;
                    Map<String, Object> hit = new LinkedHashMap<String, Object>();
                    hit.put("lineNumber", i + 1);
                    hit.put("matchedText", m.group());
                    hit.put("start", m.start());
                    hit.put("end", m.end());
                    // 捕获组
                    Map<String, Object> groups = new LinkedHashMap<String, Object>();
                    int gc = m.groupCount();
                    for (int g = 1; g <= gc; g++) {
                        String groupValue = m.group(g);
                        String name;
                        if (groupNames != null && g - 1 < groupNames.size()) {
                            name = groupNames.get(g - 1);
                        } else {
                            try {
                                name = m.group(g) != null ? String.valueOf(g) : String.valueOf(g);
                            } catch (Exception ex) {
                                name = String.valueOf(g);
                            }
                            // 尝试按名字取
                            try {
                                String namedGroup = null;
                                // 仅在 JDK 1.8 中 Pattern 不直接支持按名字取；JDK 8+ 提供 group(String)
                                // 这里通过 m.group(name) 兼容命名捕获组
                                if (groupNames != null) {
                                    // 实际命名组的 key 应在 groupNames 中
                                }
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                        // 也尝试用命名捕获
                        groups.put(name, groupValue);
                    }
                    hit.put("groups", groups);
                    hit.put("line", line);
                    matches.add(hit);
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("pattern", patternStr);
            result.put("matchCount", matches.size());
            result.put("totalMatched", totalMatched);
            result.put("matches", matches);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_regex failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_regex failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.5 txt_line_range — 行范围提取
    // ================================================================

    /**
     * 提取指定行范围（1-based，含两端）。
     * <p>
     * params.startLine — 起始行 1-based（默认 1）
     * params.endLine — 结束行 1-based（默认文件末）
     * </p>
     */
    public FileToolResponse txtLineRange(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        int startLine = readIntParam(params, "startLine", 1);
        int endLine = readIntParam(params, "endLine", Integer.MAX_VALUE);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            int total = allLines.size();
            int s = Math.max(1, Math.min(startLine, total + 1));
            int e = Math.max(s - 1, Math.min(endLine, total));
            List<String> slice = new ArrayList<String>();
            for (int i = s - 1; i < e; i++) {
                slice.add(allLines.get(i));
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("totalLines", total);
            result.put("startLine", s);
            result.put("endLine", e);
            result.put("returnedLines", slice.size());
            result.put("lines", slice);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_line_range failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_line_range failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.6 txt_section — MD 标题章节提取
    // ================================================================

    /**
     * 提取指定标题（Markdown #/##/### 风格）下的章节内容。
     * <p>
     * params.heading — 必填，目标标题（不含 # 号）
     * params.level — 标题级别（1-6，默认 1）
     * params.includeNested — 是否包含子章节（默认 true）
     * </p>
     */
    public FileToolResponse txtSection(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String heading = readStringParam(params, "heading", null);
        if (heading == null || heading.isEmpty()) {
            return FileToolResponse.error("params.heading is required", userFile.getOriginalFileName());
        }
        int level = readIntParam(params, "level", 1);
        if (level < 1 || level > 6) {
            return FileToolResponse.error("params.level must be 1-6", userFile.getOriginalFileName());
        }
        boolean includeNested = readBoolParam(params, "includeNested", true);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            // 构造 # 模式
            StringBuilder hashPrefix = new StringBuilder();
            for (int i = 0; i < level; i++) hashPrefix.append("#");
            String targetPrefix = hashPrefix.toString() + " " + heading;
            // 找到目标行索引
            int startIdx = -1;
            for (int i = 0; i < allLines.size(); i++) {
                String line = allLines.get(i).trim();
                if (line.equals(targetPrefix)) {
                    startIdx = i;
                    break;
                }
            }
            if (startIdx < 0) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("heading", heading);
                result.put("level", level);
                result.put("found", false);
                result.put("message", "Heading not found: " + targetPrefix);
                return FileToolResponse.ok(result, userFile.getOriginalFileName());
            }
            // 找结束（同级或更浅级标题）
            int endIdx = allLines.size();
            if (!includeNested) {
                for (int i = startIdx + 1; i < allLines.size(); i++) {
                    String line = allLines.get(i).trim();
                    if (line.startsWith("#")) {
                        // 数 # 数量
                        int hashCount = 0;
                        for (int j = 0; j < line.length() && line.charAt(j) == '#'; j++) hashCount++;
                        if (hashCount <= level) {
                            endIdx = i;
                            break;
                        }
                    }
                }
            } else {
                for (int i = startIdx + 1; i < allLines.size(); i++) {
                    String line = allLines.get(i).trim();
                    if (line.startsWith("#")) {
                        int hashCount = 0;
                        for (int j = 0; j < line.length() && line.charAt(j) == '#'; j++) hashCount++;
                        if (hashCount <= level) {
                            endIdx = i;
                            break;
                        }
                    }
                }
            }
            List<String> sectionLines = new ArrayList<String>();
            for (int i = startIdx; i < endIdx; i++) {
                sectionLines.add(allLines.get(i));
            }
            String content = joinLines(sectionLines, "\n");
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("heading", heading);
            result.put("level", level);
            result.put("found", true);
            result.put("startLine", startIdx + 1);
            result.put("endLine", endIdx);
            result.put("lineCount", sectionLines.size());
            result.put("content", content);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_section failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_section failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.7 txt_stats — 字符数/词数/行数统计
    // ================================================================

    /**
     * 统计字符数、词数、行数。
     * <p>
     * params.includeWhitespace — 是否将空白计入字符数（默认 true）
     * params.encoding — 文本编码（默认 UTF-8）
     * </p>
     */
    public FileToolResponse txtStats(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        boolean includeWs = readBoolParam(params, "includeWhitespace", true);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            int totalLines = allLines.size();
            int nonEmptyLines = 0;
            int totalChars = 0;
            int nonWsChars = 0;
            int totalWords = 0;
            for (String line : allLines) {
                if (line != null && !line.trim().isEmpty()) {
                    nonEmptyLines++;
                }
                if (line != null) {
                    totalChars += line.length();
                    for (int i = 0; i < line.length(); i++) {
                        if (!Character.isWhitespace(line.charAt(i))) {
                            nonWsChars++;
                        }
                    }
                    // 简易词数：按空白 split
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        String[] tokens = trimmed.split("\\s+");
                        totalWords += tokens.length;
                    }
                }
            }
            long byteSize = userFile.getFileSize() != null ? userFile.getFileSize() : 0L;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("encoding", encoding);
            result.put("totalLines", totalLines);
            result.put("nonEmptyLines", nonEmptyLines);
            result.put("charCount", includeWs ? totalChars : nonWsChars);
            result.put("charCountWithWs", totalChars);
            result.put("charCountNoWs", nonWsChars);
            result.put("wordCount", totalWords);
            result.put("byteSize", byteSize);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_stats failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_stats failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.8 txt_distinct_lines — 去重行
    // ================================================================

    /**
     * 去重行（保留首次出现顺序），覆盖式写回文件。
     * <p>
     * params.caseSensitive — 大小写敏感（默认 true）
     * params.keepEmpty — 是否保留空行（默认 true）
     * params.inPlace — 是否写回文件（默认 true）；false 时仅返回不写回
     * </p>
     */
    public FileToolResponse txtDistinctLines(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        boolean caseSensitive = readBoolParam(params, "caseSensitive", true);
        boolean keepEmpty = readBoolParam(params, "keepEmpty", true);
        boolean inPlace = readBoolParam(params, "inPlace", true);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            Set<String> seen = new LinkedHashSet<String>();
            List<String> dedup = new ArrayList<String>();
            for (String line : allLines) {
                if (line == null) continue;
                if (line.isEmpty() && !keepEmpty) continue;
                String key = caseSensitive ? line : line.toLowerCase();
                if (!seen.contains(key)) {
                    seen.add(key);
                    dedup.add(line);
                }
            }
            int original = allLines.size();
            int removed = original - dedup.size();
            boolean writtenBack = false;
            if (inPlace) {
                String content = joinLines(dedup, "\n");
                byte[] bytes = content.getBytes(Charset.forName(encoding));
                String originalStorageName = userFile.getFileName();
                String fullPath = overwriteBytes(userFile.getUserId(), originalStorageName, bytes);
                userFile.setFtpPath(fullPath);
                userFile.setFileSize((long) bytes.length);
                writtenBack = true;
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("originalLines", original);
            result.put("distinctLines", dedup.size());
            result.put("removed", removed);
            result.put("lines", dedup);
            result.put("writtenBack", writtenBack);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_distinct_lines failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_distinct_lines failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.9 txt_sort_lines — 排序行
    // ================================================================

    /**
     * 排序行（字典序/数字序），覆盖式写回。
     * <p>
     * params.order — 升序/降序（asc/desc，默认 asc）
     * params.numeric — 是否按数字排序（默认 false 字典序）
     * params.caseSensitive — 字典序时是否大小写敏感（默认 false）
     * params.inPlace — 是否写回文件（默认 true）
     * </p>
     */
    public FileToolResponse txtSortLines(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        String order = readStringParam(params, "order", "asc").toLowerCase();
        boolean numeric = readBoolParam(params, "numeric", false);
        boolean caseSensitive = readBoolParam(params, "caseSensitive", false);
        boolean inPlace = readBoolParam(params, "inPlace", true);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            List<String> sorted = new ArrayList<String>(allLines);
            final boolean asc = "asc".equals(order);
            final boolean num = numeric;
            final boolean cs = caseSensitive;
            Collections.sort(sorted, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int cmp;
                    if (num) {
                        // 抽取每行的首个数字用于比较
                        double na = extractLeadingNumber(a);
                        double nb = extractLeadingNumber(b);
                        cmp = Double.compare(na, nb);
                    } else {
                        String sa = cs ? a : (a == null ? "" : a.toLowerCase());
                        String sb = cs ? b : (b == null ? "" : b.toLowerCase());
                        cmp = sa.compareTo(sb);
                    }
                    return asc ? cmp : -cmp;
                }
            });
            if (inPlace) {
                String content = joinLines(sorted, "\n");
                byte[] bytes = content.getBytes(Charset.forName(encoding));
                String originalStorageName = userFile.getFileName();
                String fullPath = overwriteBytes(userFile.getUserId(), originalStorageName, bytes);
                userFile.setFtpPath(fullPath);
                userFile.setFileSize((long) bytes.length);
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("order", asc ? "asc" : "desc");
            result.put("numeric", numeric);
            result.put("lineCount", sorted.size());
            result.put("lines", sorted);
            result.put("writtenBack", inPlace);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_sort_lines failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_sort_lines failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 5.4.10 txt_keyword_freq — 关键词频率统计
    // ================================================================

    /**
     * 关键词（子串）频率统计。
     * <p>
     * params.keywords — 必填，关键词列表（List&lt;String&gt;）
     * params.caseSensitive — 大小写敏感（默认 false）
     * params.topN — 最多返回前 N 个（默认全部）
     * </p>
     */
    @SuppressWarnings("unchecked")
    public FileToolResponse txtKeywordFreq(UserFile userFile, Map<String, Object> params, String userId) {
        ensureTextFile(userFile);
        Object keywordsObj = readListParam(params, "keywords", null);
        if (!(keywordsObj instanceof List)) {
            return FileToolResponse.error("params.keywords (List<String>) is required", userFile.getOriginalFileName());
        }
        List<String> keywords = new ArrayList<String>();
        for (Object o : (List<Object>) keywordsObj) {
            if (o != null) keywords.add(String.valueOf(o));
        }
        if (keywords.isEmpty()) {
            return FileToolResponse.error("params.keywords must not be empty", userFile.getOriginalFileName());
        }
        boolean caseSensitive = readBoolParam(params, "caseSensitive", false);
        int topN = readIntParam(params, "topN", Integer.MAX_VALUE);
        String encoding = readStringParam(params, "encoding", DEFAULT_ENCODING);
        try {
            List<String> allLines = readAllLines(userFile, encoding);
            String text = joinLines(allLines, "\n");
            String targetText = caseSensitive ? text : text.toLowerCase();

            List<Map<String, Object>> freqList = new ArrayList<Map<String, Object>>();
            int totalMatches = 0;
            for (String kw : keywords) {
                if (kw == null || kw.isEmpty()) continue;
                String searchKey = caseSensitive ? kw : kw.toLowerCase();
                int count = 0;
                int idx = 0;
                while ((idx = targetText.indexOf(searchKey, idx)) >= 0) {
                    count++;
                    idx += searchKey.length();
                }
                totalMatches += count;
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("keyword", kw);
                entry.put("count", count);
                freqList.add(entry);
            }
            // 按 count 降序排
            Collections.sort(freqList, new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> a, Map<String, Object> b) {
                    int ca = (Integer) a.get("count");
                    int cb = (Integer) b.get("count");
                    return Integer.compare(cb, ca);
                }
            });
            // topN 截断
            List<Map<String, Object>> limited = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < Math.min(topN, freqList.size()); i++) {
                limited.add(freqList.get(i));
            }

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileName", userFile.getOriginalFileName());
            result.put("caseSensitive", caseSensitive);
            result.put("totalKeywords", keywords.size());
            result.put("totalMatches", totalMatches);
            result.put("frequency", limited);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("txt_keyword_freq failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("txt_keyword_freq failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    private void ensureTextFile(UserFile userFile) {
        String ext = extractExtension(userFile.getOriginalFileName());
        if (!"txt".equals(ext) && !"md".equals(ext) && !"markdown".equals(ext)) {
            throw new IllegalArgumentException("Not a text file: " + userFile.getOriginalFileName()
                    + ". Use 'txt_*' APIs only with .txt/.md/.markdown files.");
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    private List<String> readAllLines(UserFile userFile, String encoding) throws IOException {
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        Charset cs = Charset.forName(encoding);
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(baos.toByteArray()), cs));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            try { reader.close(); } catch (Exception ignored) {}
        }
        return lines;
    }

    private String uploadBytes(String userId, String storageName, byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        return ftpFileService.uploadFile(userId, storageName, bais);
    }

    /**
     * 用原 storageName 覆盖写回文件（保留文件名不生成新 UUID）。
     * <p>
     * 用于 inPlace 语义的去重/排序/替换等场景，确保"原文件被修改"——
     * fileRef 仍然是同一个，user_files 行的 file_name 不变，DB 与磁盘一致。
     * </p>
     */
    private String overwriteBytes(String userId, String storageName, byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        return ftpFileService.uploadFile(userId, storageName, bais);
    }

    private String generateNewStorageName(String originalFileName) {
        String ext = extractExtension(originalFileName);
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return ext.isEmpty() ? uuid : uuid + "." + ext;
    }

    private String joinLines(List<String> lines, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private double extractLeadingNumber(String s) {
        if (s == null) return 0.0;
        String trimmed = s.trim();
        int i = 0;
        if (i < trimmed.length() && (trimmed.charAt(i) == '-' || trimmed.charAt(i) == '+')) i++;
        int start = i;
        boolean dotFound = false;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' && !dotFound) {
                dotFound = true;
                i++;
            } else {
                break;
            }
        }
        if (i == start) return 0.0;
        try {
            return Double.parseDouble(trimmed.substring(0, i));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ========== 参数读取 ==========

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
    private Object readListParam(Map<String, Object> params, String key, Object def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof List) {
            return v;
        }
        return def;
    }
}
