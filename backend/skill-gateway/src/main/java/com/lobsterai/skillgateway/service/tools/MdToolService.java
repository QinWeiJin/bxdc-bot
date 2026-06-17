package com.lobsterai.skillgateway.service.tools;

import com.lobsterai.skillgateway.config.FtpConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.service.FileToolService;
import com.lobsterai.skillgateway.service.FtpFileService;

import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.Visitor;
import com.vladsch.flexmark.util.ast.VisitHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Markdown 文件扩展工具（5.5 / 模块四 §4）。
 * <p>
 * 提供 9 个 API：md_images、md_headings、md_table、md_list_items、md_tasks、
 * md_emphasis、md_toc、md_filter_section、md_merge。所有方法注册到
 * {@link FileToolService} 的统一调度入口，通过 kind=file_tool 链路被 LLM 调用。
 * </p>
 *
 * <h3>解析层</h3>
 * <ul>
 *   <li>使用 flexmark-java 0.62.2 的 5 个 module：flexmark core + 4 个 GFM ext</li>
 *   <li>9 个 handler 共享 1 个 {@code static final Parser} 实例（线程安全、immutable）</li>
 *   <li>{@link NodeVisitor} 一次性遍历 AST 收集结构化信息</li>
 * </ul>
 *
 * <h3>JDK 1.8 兼容约束</h3>
 * <ul>
 *   <li>不用 {@code var}、{@code List.of()}、{@code switch} 表达式、Records</li>
 *   <li>Map/List 显式声明泛型</li>
 *   <li>字符串拼接用 {@code StringBuilder} 或 {@code +}</li>
 * </ul>
 */
@Service
public class MdToolService {

    private static final Logger log = LoggerFactory.getLogger(MdToolService.class);

    /** flexmark Parser 单例（线程安全，immutable DataSet） */
    private static final Parser MD_PARSER = Parser.builder()
            .extensions(Arrays.asList(
                    TablesExtension.create(),
                    TaskListExtension.create(),
                    StrikethroughExtension.create(),
                    YamlFrontMatterExtension.create()))
            .build();

    private final FileToolService fileToolService;
    private final FtpFileService ftpFileService;
    private final UserFileMapper userFileMapper;
    private final FtpConfig ftpConfig;

    @Autowired
    public MdToolService(FileToolService fileToolService,
                         FtpFileService ftpFileService,
                         UserFileMapper userFileMapper,
                         FtpConfig ftpConfig) {
        this.fileToolService = fileToolService;
        this.ftpFileService = ftpFileService;
        this.userFileMapper = userFileMapper;
        this.ftpConfig = ftpConfig;
    }

    /** Spring 启动后自动注册到 FileToolService。 */
    @PostConstruct
    public void registerHandlers() {
        fileToolService.registerHandler("md_images", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdImages(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_headings", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdHeadings(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_table", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdTable(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_list_items", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdListItems(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_tasks", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdTasks(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_emphasis", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdEmphasis(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_toc", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdToc(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_filter_section", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdFilterSection(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_merge", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdMerge(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_init_temp", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdInitTemp(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_read", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdRead(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("md_write", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return mdWrite(userFile, params, userId);
            }
        });
        log.info("MdToolService registered 12 handlers: md_init_temp/read/write/images/headings/table/list_items/tasks/emphasis/toc/filter_section/merge");
    }

    // ================================================================
    // 公共 helper
    // ================================================================

    private void ensureMdFile(UserFile userFile) {
        String ext = extractExtension(userFile.getOriginalFileName());
        if (!"md".equalsIgnoreCase(ext) && !"markdown".equalsIgnoreCase(ext)) {
            throw new IllegalArgumentException("only .md/.markdown files are supported (got: " + userFile.getOriginalFileName() + ")");
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1);
    }

    private String readAllText(UserFile userFile) throws IOException {
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        return new String(baos.toByteArray(), Charset.forName("UTF-8"));
    }

    /**
     * 递归提取 Node 的所有文本内容（合并 inline Text 节点）。
     */
    private String extractTextContent(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text) {
                sb.append(((Text) child).getChars().toString());
            } else {
                sb.append(extractTextContent(child));
            }
        }
        return sb.toString();
    }

    /**
     * 把 content 写到新文件 + 写 user_files 行，返回含 {originalFileId, newFileId, newFileName, downloadUrl, ...} 的响应。
     *
     * <p>
     * 此方法已废弃：建议调用方先 md_init_temp 创建临时文件，再通过修改类操作（md_filter_section / md_merge）
     * 就地覆盖同一个临时文件，最终返回 tempFileId 对应的 downloadUrl。
     * 保留此方法供 md_merge 等需要创建独立新文件的场景使用（当 sourceFileId 为空时先 init temp 更佳）。
     * </p>
     *
     * @deprecated 推荐先 {@link #mdInitTemp} 后直接在临时文件上修改
     */
    @Deprecated
    private FileToolResponse writeBackNewFile(UserFile userFile, String userId, String content) throws IOException {
        byte[] bytes = content.getBytes(Charset.forName("UTF-8"));
        String fullPath = ftpFileService.uploadFile(userId, userFile.getOriginalFileName(),
                new ByteArrayInputStream(bytes));
        // 从 FTP 返回的完整路径提取实际存储文件名（如 "/files/811003/a1b2c3d4.md" → "a1b2c3d4.md"）
        String actualFileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);

        UserFile newFile = new UserFile();
        newFile.setUserId(userId);
        newFile.setOriginalFileName(userFile.getOriginalFileName());
        newFile.setFileName(actualFileName);
        newFile.setFileSize((long) bytes.length);
        newFile.setFileType(userFile.getFileType());
        newFile.setFtpPath(fullPath);
        newFile.setUploadTime(java.time.LocalDateTime.now());
        userFileMapper.insert(newFile);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("originalFileId", userFile.getId());
        result.put("newFileId", newFile.getId());
        result.put("newFileName", newFile.getOriginalFileName());
        result.put("originalFileName", newFile.getOriginalFileName());
        result.put("sourceFileName", userFile.getOriginalFileName());
        result.put("ftpPath", fullPath);
        result.put("lineCount", content.split("\n", -1).length);
        return FileToolResponse.ok(result, userFile.getOriginalFileName());
    }

    @SuppressWarnings("unchecked")
    private List<Object> readListParam(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringListParam(Map<String, Object> params, String key) {
        List<Object> raw = readListParam(params, key);
        if (raw == null) return null;
        List<String> out = new ArrayList<String>();
        for (Object o : raw) {
            out.add(o == null ? null : String.valueOf(o));
        }
        return out;
    }

    private String readStringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private boolean readBoolParam(Map<String, Object> params, String key, boolean def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    private int readIntParam(Map<String, Object> params, String key, int def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong((String) o); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * 提取文件首段 YAML frontmatter（含 --- ... ---），若无则返回 null。
     */
    private String extractFrontmatter(String content) {
        if (content == null) return null;
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) return null;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j <= i; j++) {
                    sb.append(lines[j]);
                    if (j < i) sb.append("\n");
                }
                return sb.toString();
            }
        }
        return null;
    }

    private String joinLinesRange(String[] lines, int from, int to) {
        if (from >= to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < lines.length; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    // ================================================================
    // md_init_temp — 初始化临时文件（创建副本，后续操作在其上进行）
    // ================================================================

    /**
     * 根据源文件创建临时文件副本（上传到 FTP + 写 user_files 行），后续操作均在此临时文件上进行。
     *
     * @param userFile 源文件实体
     * @param params   参数：无
     * @param userId   用户 ID
     * @return 临时文件信息，包含 fileId、sourceFileId、downloadUrl
     */
    public FileToolResponse mdInitTemp(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            Long sourceFileId = userFile.getId();

            // 读取源文件内容
            String content = readAllText(userFile);
            byte[] fileBytes = content.getBytes(Charset.forName("UTF-8"));

            // 生成临时文件名
            String tempFileName = getTempFileName(userFile.getOriginalFileName());

            // 上传临时文件到 FTP
            String ftpPath = ftpFileService.uploadFile(userId, tempFileName, new ByteArrayInputStream(fileBytes));
            String storageFileName = ftpPath.substring(ftpPath.lastIndexOf('/') + 1);

            // 在 user_files 表中创建新记录
            UserFile tempUserFile = new UserFile();
            tempUserFile.setUserId(userId);
            tempUserFile.setOriginalFileName(tempFileName);
            tempUserFile.setFileName(storageFileName);
            tempUserFile.setFileSize((long) fileBytes.length);
            tempUserFile.setFileType(userFile.getFileType());
            tempUserFile.setFtpPath(ftpPath);
            tempUserFile.setSourceFileId(sourceFileId);
            tempUserFile.setUploadTime(java.time.LocalDateTime.now());
            userFileMapper.insert(tempUserFile);

            Long tempFileId = tempUserFile.getId();

            // 生成带签名的下载 URL（浏览器可直接点击，无需 X-User-Id header）
            String downloadUrl = ftpConfig.buildDownloadUrl(tempFileId, userId);
            tempUserFile.setDownloadUrl(downloadUrl);
            userFileMapper.updateById(tempUserFile);

            log.info("md_init_temp created temp file: id={}, sourceFileId={}, tempFileName={}, downloadUrl={}",
                    tempFileId, sourceFileId, tempFileName, downloadUrl);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "临时文件初始化成功");
            result.put("fileId", tempFileId);
            result.put("sourceFileId", sourceFileId);
            result.put("fileName", tempFileName);
            result.put("filePath", ftpPath);
            result.put("downloadUrl", downloadUrl);

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("md_init_temp failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_init_temp failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    /**
     * 生成临时文件名（格式：原文件名_temp.扩展名）。
     */
    private String getTempFileName(String originalFileName) {
        int dotIndex = originalFileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? originalFileName.substring(0, dotIndex) : originalFileName;
        String extension = dotIndex > 0 ? originalFileName.substring(dotIndex) : ".md";
        return baseName + "_temp" + extension;
    }

    // ================================================================
    // md_read — 读取 Markdown 文件全文
    // ================================================================

    /**
     * 读取 Markdown 文件全文内容。
     * <p>
     * 配合 {@link #mdInitTemp} 使用：先在源文件上创建临时文件，再对临时文件读写操作。
     * 只读操作不修改文件，直接返回 fileId + downloadUrl + 文件内容。
     * </p>
     *
     * @param userFile 文件实体
     * @param params   参数：encoding（编码，默认 UTF-8）、maxChars（最大返回字符数，默认不限制）
     * @param userId   用户 ID
     * @return 文件全文 + fileId + downloadUrl
     */
    public FileToolResponse mdRead(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        String encoding = readStringParam(params, "encoding", "UTF-8");
        int maxChars = readIntParam(params, "maxChars", Integer.MAX_VALUE);
        try {
            String content;
            if ("UTF-8".equalsIgnoreCase(encoding)) {
                content = readAllText(userFile);
            } else {
                ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
                content = new String(baos.toByteArray(), Charset.forName(encoding));
            }
            int totalChars = content.length();
            boolean truncated = totalChars > maxChars;
            String displayContent = truncated ? content.substring(0, maxChars) : content;

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("filePath", userFile.getFtpPath());
            result.put("fileName", userFile.getOriginalFileName());
            result.put("encoding", encoding);
            result.put("totalChars", totalChars);
            result.put("totalLines", content.split("\n", -1).length);
            result.put("content", displayContent);
            result.put("truncated", truncated);

            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_read failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_read failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // md_write — 覆盖写入 Markdown 文件
    // ================================================================

    /**
     * 覆盖写入 Markdown 文件内容。
     * <p>
     * 操作模式与 TxtToolService.txtWrite 一致：
     * <ul>
     *   <li>临时文件（sourceFileId != null）：覆盖写回同一个 FTP 文件，fileId 不变</li>
     *   <li>源文件（sourceFileId == null）：自动创建临时文件，返回新 fileId</li>
     * </ul>
     * 返回 fileId + downloadUrl + filePath，供后续操作或下载使用。
     * </p>
     *
     * @param userFile 文件实体（需先在源文件上调用 {@link #mdInitTemp} 获得临时文件）
     * @param params   参数：content（必填，Markdown 文本内容）、encoding（编码，默认 UTF-8）
     * @param userId   用户 ID
     * @return fileId + downloadUrl + filePath + 写入统计
     */
    public FileToolResponse mdWrite(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        String content = readStringParam(params, "content", null);
        if (content == null) {
            return FileToolResponse.error("params.content is required", userFile.getOriginalFileName());
        }
        String encoding = readStringParam(params, "encoding", "UTF-8");
        try {
            Map<String, Object> saveResult = saveAndReturnResult(content, userFile, userId);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Markdown file written successfully");
            result.putAll(saveResult);
            result.put("encoding", encoding);
            result.put("lineCount", content.split("\n", -1).length);
            result.put("totalChars", content.length());

            return FileToolResponse.ok(result, saveResult.get("fileName").toString());
        } catch (Exception e) {
            log.error("md_write failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_write failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    /**
     * 用原 storageName 覆盖写回 FTP（不生成新 UUID 文件名）。
     * <p>
     * 用于临时文件场景：修改操作直接覆盖同一个 FTP 文件，
     * user_files 行的 file_name 不变，DB 与磁盘一致。
     * </p>
     */
    private String overwriteBytes(String userId, String storageName, byte[] bytes) throws IOException {
        return ftpFileService.uploadFileWithFileName(userId, storageName, new ByteArrayInputStream(bytes));
    }

    /**
     * 保存修改后的内容并生成结果信息（fileId / downloadUrl / filePath）。
     *
     * <p>策略：</p>
     * <ul>
     *   <li>临时文件（sourceFileId != null）：覆盖写回同一 FTP 文件，返回当前 fileId 的 downloadUrl</li>
     *   <li>源文件（sourceFileId == null）：创建新临时文件（先 INSERT user_files 再覆盖写入 FTP），
     *       返回新文件 fileId 的 downloadUrl</li>
     * </ul>
     *
     * @param content  修改后的文本内容
     * @param userFile 当前文件实体
     * @param userId   用户 ID
     * @return 包含 fileId、downloadUrl、fileName、filePath 的 Map
     */
    private Map<String, Object> saveAndReturnResult(String content, UserFile userFile, String userId) throws IOException {
        String baseName = userFile != null ? userFile.getOriginalFileName() : "merged";
        return saveAndReturnResult(content, userFile, userId, baseName);
    }

    /**
     * 保存修改后的内容并生成结果信息（可指定显示名）。
     *
     * @param content         修改后的文本内容
     * @param userFile        当前文件实体（可为 null）
     * @param userId          用户 ID
     * @param baseDisplayName 文件显示名（如 "A_+_B_merged"），决定 UserFile.originalFileName
     * @return 包含 fileId、downloadUrl、fileName、filePath 的 Map
     */
    private Map<String, Object> saveAndReturnResult(String content, UserFile userFile, String userId,
                                                     String baseDisplayName) throws IOException {
        byte[] bytes = content.getBytes(Charset.forName("UTF-8"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        Long sourceFileId = userFile == null ? null : userFile.getSourceFileId();
        Long resultFileId;
        String ftpPath;
        String resultFileName;

        if (sourceFileId != null) {
            // 临时文件：覆盖写回同一个 FTP 文件
            String storageFileName = userFile.getFileName();
            ftpPath = overwriteBytes(userId, storageFileName, bytes);
            resultFileId = userFile.getId();
            resultFileName = userFile.getOriginalFileName();

            log.info("md saveAndReturnResult overwrote temp file: fileId={}, storageFileName={}", resultFileId, storageFileName);
        } else {
            // 源文件 / 无 fileRef（userFile == null）：创建新文件
            String fileType = userFile != null ? userFile.getFileType() : "md";
            String tempFileName = getTempFileName(baseDisplayName);
            ftpPath = ftpFileService.uploadFile(userId, tempFileName, new ByteArrayInputStream(bytes));
            String storageFileName = ftpPath.substring(ftpPath.lastIndexOf('/') + 1);

            UserFile tempUserFile = new UserFile();
            tempUserFile.setUserId(userId);
            tempUserFile.setOriginalFileName(tempFileName);
            tempUserFile.setFileName(storageFileName);
            tempUserFile.setFileSize((long) bytes.length);
            tempUserFile.setFileType(fileType);
            tempUserFile.setFtpPath(ftpPath);
            // userFile == null 表示全新合并（md_merge），不挂 sourceFileId
            tempUserFile.setSourceFileId(userFile == null ? null : userFile.getId());
            tempUserFile.setIsToolGenerated(1);
            tempUserFile.setUploadTime(java.time.LocalDateTime.now());
            userFileMapper.insert(tempUserFile);

            resultFileId = tempUserFile.getId();
            resultFileName = tempFileName;

            log.info("md saveAndReturnResult created new temp file: fileId={}, tempFileName={}, userFileNull={}",
                    resultFileId, tempFileName, userFile == null);
        }

        String downloadUrl = ftpConfig.buildDownloadUrl(resultFileId, userId);

        // 回写 downloadUrl + 文件大小/路径 到 DB（与 Word/Txt 一致，单次 UPDATE）
        if (sourceFileId == null) {
            UserFile updateFile = new UserFile();
            updateFile.setId(resultFileId);
            updateFile.setDownloadUrl(downloadUrl);
            userFileMapper.updateById(updateFile);
        } else {
            userFile.setFileSize((long) bytes.length);
            userFile.setFtpPath(ftpPath);
            userFile.setDownloadUrl(downloadUrl);
            userFileMapper.updateById(userFile);
        }

        result.put("fileId", resultFileId);
        result.put("downloadUrl", downloadUrl);
        result.put("fileName", resultFileName);
        result.put("filePath", ftpPath);
        result.put("size", bytes.length);

        return result;
    }

    // ================================================================
    // 9 个 md_* handler（+ md_init_temp 首个调用）
    // ================================================================

    // ----- md_images -----
    public FileToolResponse mdImages(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> images = new ArrayList<Map<String, Object>>();

            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(Image.class, new Visitor<Image>() {
                        @Override
                        public void visit(Image img) {
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("lineNumber", img.getStartLineNumber() + 1);
                            m.put("alt", img.getText().toString());
                            m.put("title", img.getTitle().toString());
                            m.put("url", img.getUrl().toString());
                            m.put("kind", "inline");
                            images.add(m);
                        }
                    })
            );
            visitor.visitChildren(document);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("count", images.size());
            result.put("images", images);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_images failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_images failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ----- md_headings -----
    public FileToolResponse mdHeadings(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> headings = new ArrayList<Map<String, Object>>();

            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(Heading.class, new Visitor<Heading>() {
                        @Override
                        public void visit(Heading h) {
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("level", h.getLevel());
                            m.put("text", extractTextContent(h).trim());
                            m.put("lineNumber", h.getStartLineNumber() + 1);
                            headings.add(m);
                        }
                    })
            );
            visitor.visitChildren(document);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("count", headings.size());
            result.put("headings", headings);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_headings failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_headings failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ----- md_table -----
    public FileToolResponse mdTable(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> tables = new ArrayList<Map<String, Object>>();

            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(TableBlock.class, new Visitor<TableBlock>() {
                        @Override
                        public void visit(TableBlock tb) {
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("lineNumber", tb.getStartLineNumber() + 1);
                            m.put("rawMarkdown", tb.getContentChars().toString());

                            List<List<String>> rows = new ArrayList<List<String>>();
                            List<String> header = null;
                            // flexmark 0.62.2 TableBlock 的子结构是：
                            //   TableHead → TableRow（header 1 行）
                            //   TableSeparator（占位，| --- | --- |）— 跳过
                            //   TableBody → TableRow[]（data 多行）
                            for (Node sectionNode = tb.getFirstChild(); sectionNode != null; sectionNode = sectionNode.getNext()) {
                                if (sectionNode instanceof TableSeparator) {
                                    // 跳过 separator 行（| --- | --- |）
                                    continue;
                                }
                                if (sectionNode instanceof TableRow) {
                                    // 直接是 TableRow（兼容老 flexmark）
                                    rows.add(extractCells(sectionNode));
                                } else {
                                    // TableHead / TableBody：含多个 TableRow children
                                    for (Node rowNode = sectionNode.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                                        if (rowNode instanceof TableRow) {
                                            rows.add(extractCells(rowNode));
                                        }
                                    }
                                }
                            }
                            if (!rows.isEmpty()) {
                                header = rows.get(0);
                                rows.remove(0);
                            }
                            m.put("header", header != null ? header : new ArrayList<String>());
                            m.put("rows", rows);
                            tables.add(m);
                        }

                        private List<String> extractCells(Node rowNode) {
                            List<String> cells = new ArrayList<String>();
                            for (Node cellNode = rowNode.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                                if (cellNode instanceof TableCell) {
                                    TableCell tc = (TableCell) cellNode;
                                    cells.add(tc.getText().toString().trim());
                                }
                            }
                            return cells;
                        }
                    })
            );
            visitor.visitChildren(document);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("count", tables.size());
            result.put("tables", tables);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_table failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_table failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ----- md_list_items -----
    public FileToolResponse mdListItems(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();

            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(ListItem.class, new Visitor<ListItem>() {
                        @Override
                        public void visit(ListItem li) {
                            Node parent = li.getParent();
                            boolean ordered = false;
                            String marker = "-";
                            int startNumber = 0;
                            char delimiter = '.';
                            if (parent instanceof OrderedList) {
                                OrderedList ol = (OrderedList) parent;
                                ordered = true;
                                startNumber = ol.getStartNumber();
                                delimiter = ol.getDelimiter();
                                marker = startNumber + String.valueOf(delimiter);
                            } else if (parent instanceof BulletList) {
                                BulletList bl = (BulletList) parent;
                                marker = String.valueOf(bl.getOpeningMarker());
                            }
                            String text = extractTextContent(li).trim();
                            int leadingSpaces = 0;
                            for (int i = 0; i < text.length() && text.charAt(i) == ' '; i++) {
                                leadingSpaces++;
                            }
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("lineNumber", li.getStartLineNumber() + 1);
                            m.put("marker", marker);
                            m.put("text", text);
                            m.put("indent", leadingSpaces);
                            m.put("ordered", ordered);
                            items.add(m);
                        }
                    }),
                    new VisitHandler<>(TaskListItem.class, new Visitor<TaskListItem>() {
                        @Override
                        public void visit(TaskListItem node) {
                            // TaskListItem 由 md_tasks 处理，这里跳过
                        }
                    })
            );
            visitor.visitChildren(document);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("count", items.size());
            result.put("items", items);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_list_items failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_list_items failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ----- md_tasks -----
    public FileToolResponse mdTasks(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();

            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(TaskListItem.class, new Visitor<TaskListItem>() {
                        @Override
                        public void visit(TaskListItem tli) {
                            // flexmark 0.62.2 的 TaskListItem.getContentChars() 返空（bug）
                            // 用 getChars() 拿整个节点的源文本（含 marker + 子 list），
                            // 截第一行就是 task 文本（嵌套子 list 在后续行）
                            String rawLine = tli.getChars().toString();
                            int newline = rawLine.indexOf('\n');
                            if (newline >= 0) {
                                rawLine = rawLine.substring(0, newline);
                            }
                            rawLine = rawLine.trim();
                            int leadingSpaces = 0;
                            for (int i = 0; i < rawLine.length() && rawLine.charAt(i) == ' '; i++) {
                                leadingSpaces++;
                            }
                            String text = rawLine;
                            int idx = 0;
                            while (idx < text.length() && (text.charAt(idx) == ' ' || text.charAt(idx) == '\t')) idx++;
                            if (idx < text.length() && (text.charAt(idx) == '-' || text.charAt(idx) == '*' || text.charAt(idx) == '+')) {
                                idx++;
                                while (idx < text.length() && text.charAt(idx) == ' ') idx++;
                                if (idx < text.length() && text.charAt(idx) == '[') {
                                    int close = text.indexOf(']', idx);
                                    if (close > 0) {
                                        idx = close + 1;
                                        while (idx < text.length() && text.charAt(idx) == ' ') idx++;
                                    }
                                }
                                text = text.substring(idx);
                            }
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("lineNumber", tli.getStartLineNumber() + 1);
                            m.put("checked", tli.isItemDoneMarker());
                            m.put("text", text);
                            m.put("indent", leadingSpaces);
                            m.put("rawLine", rawLine);
                            tasks.add(m);
                        }
                    })
            );
            visitor.visitChildren(document);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("count", tasks.size());
            result.put("tasks", tasks);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_tasks failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_tasks failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ----- md_emphasis -----
    public FileToolResponse mdEmphasis(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> spans = new ArrayList<Map<String, Object>>();

            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(StrongEmphasis.class, new Visitor<StrongEmphasis>() {
                        @Override
                        public void visit(StrongEmphasis node) {
                            addSpan(spans, node, "bold");
                        }
                    }),
                    new VisitHandler<>(Emphasis.class, new Visitor<Emphasis>() {
                        @Override
                        public void visit(Emphasis node) {
                            addSpan(spans, node, "italic");
                        }
                    }),
                    new VisitHandler<>(Strikethrough.class, new Visitor<Strikethrough>() {
                        @Override
                        public void visit(Strikethrough node) {
                            addSpan(spans, node, "strikethrough");
                        }
                    }),
                    new VisitHandler<>(Code.class, new Visitor<Code>() {
                        @Override
                        public void visit(Code node) {
                            addSpan(spans, node, "code");
                        }
                    })
            );
            visitor.visitChildren(document);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("count", spans.size());
            result.put("spans", spans);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_emphasis failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_emphasis failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    private void addSpan(List<Map<String, Object>> spans, Node node, String style) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("lineNumber", node.getStartLineNumber() + 1);
        m.put("style", style);
        m.put("text", extractTextContent(node));
        spans.add(m);
    }

    // ----- md_toc -----
    public FileToolResponse mdToc(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Map<String, Object>> flat = new ArrayList<Map<String, Object>>();
            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(Heading.class, new Visitor<Heading>() {
                        @Override
                        public void visit(Heading h) {
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("level", h.getLevel());
                            m.put("text", extractTextContent(h).trim());
                            m.put("lineNumber", h.getStartLineNumber() + 1);
                            flat.add(m);
                        }
                    })
            );
            visitor.visitChildren(document);
            List<Map<String, Object>> toc = buildTocTree(flat);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fileId", userFile.getId());
            result.put("downloadUrl", ftpConfig.buildDownloadUrl(userFile.getId(), userId));
            result.put("fileName", userFile.getOriginalFileName());
            result.put("headingCount", flat.size());
            result.put("toc", toc);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_toc failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_toc failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTocTree(List<Map<String, Object>> flat) {
        List<Map<String, Object>> roots = new ArrayList<Map<String, Object>>();
        Stack<Map<String, Object>> stack = new Stack<Map<String, Object>>();

        for (Map<String, Object> h : flat) {
            int level = (Integer) h.get("level");
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("level", level);
            node.put("text", h.get("text"));
            node.put("lineNumber", h.get("lineNumber"));
            node.put("children", new ArrayList<Map<String, Object>>());

            while (!stack.isEmpty()) {
                int topLevel = (Integer) stack.peek().get("level");
                if (topLevel < level) {
                    break;
                }
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                List<Map<String, Object>> parentChildren = (List<Map<String, Object>>) stack.peek().get("children");
                parentChildren.add(node);
            }
            stack.push(node);
        }
        return roots;
    }

    // ----- md_filter_section -----
    public FileToolResponse mdFilterSection(UserFile userFile, Map<String, Object> params, String userId) {
        ensureMdFile(userFile);
        try {
            // 读取 keep 或 remove（二选一）
            List<String> keepTargets = readStringListParam(params, "keep");
            List<String> removeTargets = readStringListParam(params, "remove");
            boolean hasKeep = keepTargets != null && !keepTargets.isEmpty();
            boolean hasRemove = removeTargets != null && !removeTargets.isEmpty();

            if (!hasKeep && !hasRemove) {
                return FileToolResponse.error(
                        "Either 'keep' or 'remove' is required (non-empty string array of heading texts)",
                        userFile.getOriginalFileName());
            }
            // keep 优先于 remove
            boolean isKeep = hasKeep;
            List<String> targetHeadings = isKeep ? keepTargets : removeTargets;

            String content = readAllText(userFile);
            Node document = MD_PARSER.parse(content);
            final List<Heading> allHeadings = new ArrayList<Heading>();
            NodeVisitor visitor = new NodeVisitor(
                    new VisitHandler<>(Heading.class, new Visitor<Heading>() {
                        @Override
                        public void visit(Heading h) {
                            allHeadings.add(h);
                        }
                    })
            );
            visitor.visitChildren(document);

            // 为每个 target heading 文本找到匹配的 Heading 节点，计算节范围 [startLine, endLine)
            // key=heading文本, value=int[]{startLine, endLine}（0-based 行号，start 不含标题行本身）
            List<int[]> ranges = new ArrayList<int[]>();
            for (String target : targetHeadings) {
                Heading found = null;
                for (Heading h : allHeadings) {
                    if (target.equals(extractTextContent(h).trim())) {
                        found = h;
                        break;
                    }
                }
                if (found == null) {
                    return FileToolResponse.error("Heading not found: \"" + target + "\"",
                            userFile.getOriginalFileName());
                }
                int startIdx = found.getStartLineNumber() + 1; // 标题下一行开始（0-based）
                int endIdx = -1;
                int sectionLevel = found.getLevel();
                for (Heading h : allHeadings) {
                    int hLine = h.getStartLineNumber() + 1;
                    if (hLine > startIdx && h.getLevel() <= sectionLevel) {
                        endIdx = hLine;
                        break;
                    }
                }
                if (endIdx == -1) {
                    endIdx = content.split("\n", -1).length + 1;
                }
                ranges.add(new int[]{startIdx, endIdx});
            }

            // 按起始行排序
            java.util.Collections.sort(ranges, new java.util.Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return Integer.compare(a[0], b[0]);
                }
            });

            String[] lines = content.split("\n", -1);
            StringBuilder out = new StringBuilder();

            // 提取 frontmatter
            int contentStart = 0;
            if (lines.length > 0 && "---".equals(lines[0].trim())) {
                for (int i = 1; i < lines.length; i++) {
                    if ("---".equals(lines[i].trim())) {
                        for (int j = 0; j <= i; j++) {
                            out.append(lines[j]).append("\n");
                        }
                        contentStart = i + 1;
                        break;
                    }
                }
            }

            if (isKeep) {
                // keep: 输出 frontmatter + 每个匹配节的完整内容
                for (int[] range : ranges) {
                    int from = Math.max(range[0], contentStart);
                    out.append(joinLinesRange(lines, from, range[1]));
                }
            } else {
                // remove: 输出 frontmatter + 非匹配区域
                int cursor = contentStart;
                for (int[] range : ranges) {
                    // range[0] 是标题的 startLineNumber+1，即内容起始行
                    // 需要也删掉标题行本身
                    int titleLine = range[0] - 1; // 标题行自身
                    if (titleLine >= cursor) {
                        out.append(joinLinesRange(lines, cursor, titleLine));
                    }
                    cursor = Math.max(cursor, range[1]);
                }
                if (cursor < lines.length) {
                    out.append(joinLinesRange(lines, cursor, lines.length));
                }
            }

            String newContent = out.toString().replaceAll("\\n+$", "") + "\n";

            Map<String, Object> saveResult = saveAndReturnResult(newContent, userFile, userId);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", isKeep ? "Section(s) kept" : "Section(s) removed");
            result.putAll(saveResult);
            result.put("targetHeadings", targetHeadings);
            result.put("mode", isKeep ? "keep" : "remove");
            result.put("lineCount", newContent.split("\n", -1).length);
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("md_filter_section failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("md_filter_section failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ----- md_merge -----
    /**
     * 安全解析 sourceFileIds 参数，兼容 LLM 把整个对象序列化为 JSON string 传过来的情况。
     * <p>
     * LLM 经常把 {@code sourceFileIds} 传成 JSON 字符串 {@code "[78, 79]"}，而不是 List，
     * 因此先用 {@link ObjectMapper} 反序列化一次。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private List<Object> parseSourceFileIdsParam(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List) {
            return (List<Object>) raw;
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                ObjectMapper mapper = new ObjectMapper();
                Object parsed = mapper.readValue(s, Object.class);
                if (parsed instanceof List) {
                    return (List<Object>) parsed;
                }
                log.warn("md_merge sourceFileIds JSON parsed to non-list: {} (type={})", s, parsed == null ? "null" : parsed.getClass().getSimpleName());
                return null;
            } catch (Exception e) {
                log.warn("md_merge failed to parse sourceFileIds JSON string: {}", s, e);
                return null;
            }
        }
        return null;
    }

    public FileToolResponse mdMerge(UserFile userFile, Map<String, Object> params, String userId) {
        // userFile 可空：md_merge 基于 sourceFileIds 合并生成新文件，不需要在某个已有文件上操作
        if (userFile != null) {
            ensureMdFile(userFile);
        }
        try {
            // 兼容 List / JSON String 两种入参形式
            List<Object> sourceIdsRaw = parseSourceFileIdsParam(params.get("sourceFileIds"));
            if (sourceIdsRaw == null || sourceIdsRaw.size() < 2) {
                return FileToolResponse.error("sourceFileIds must contain at least 2 file ids (got: " + sourceIdsRaw + ")",
                        userFile != null ? userFile.getOriginalFileName() : "md_merge");
            }
            String conflictStrategy = readStringParam(params, "frontmatterConflict", "error");
            // 兼容 Boolean / String 两种入参
            boolean prefixHeaders = readBoolParam(params, "prefixHeaders", true);

            List<UserFile> sources = new ArrayList<UserFile>();
            List<String> sourceContents = new ArrayList<String>();
            List<String> sourceFrontmatters = new ArrayList<String>();
            // 内部 helper：保证 userFile 为 null 时 error 响应能给出合理 fallback 名
            String errFileName = userFile != null ? userFile.getOriginalFileName() : "md_merge";

            for (Object idObj : sourceIdsRaw) {
                Long fileId = toLong(idObj);
                if (fileId == null) {
                    return FileToolResponse.error("invalid sourceFileIds entry: " + idObj, errFileName);
                }
                UserFile src = userFileMapper.selectById(fileId);
                if (src == null) {
                    return FileToolResponse.error("source file not found: id=" + fileId, errFileName);
                }
                if (!userId.equals(src.getUserId())) {
                    return FileToolResponse.error("access denied for file id " + fileId, errFileName);
                }
                String ft = src.getFileType();
                if (!"md".equalsIgnoreCase(ft) && !"markdown".equalsIgnoreCase(ft)) {
                    return FileToolResponse.error("all source files must be .md/.markdown (file " + src.getOriginalFileName() + " is " + ft + ")", errFileName);
                }
                sources.add(src);
                String srcContent = readAllText(src);
                sourceContents.add(srcContent);
                sourceFrontmatters.add(extractFrontmatter(srcContent));
            }

            String keptFrontmatter = null;
            String keptFrom = null;
            int fmCount = 0;
            for (int i = 0; i < sourceFrontmatters.size(); i++) {
                if (sourceFrontmatters.get(i) != null) fmCount++;
            }
            if (fmCount > 1) {
                if ("error".equalsIgnoreCase(conflictStrategy)) {
                    return FileToolResponse.error("frontmatter conflict: " + fmCount + " source files contain frontmatter", errFileName);
                } else if ("first".equalsIgnoreCase(conflictStrategy)) {
                    for (int i = 0; i < sourceFrontmatters.size(); i++) {
                        if (sourceFrontmatters.get(i) != null) {
                            keptFrontmatter = sourceFrontmatters.get(i);
                            keptFrom = sources.get(i).getOriginalFileName();
                            break;
                        }
                    }
                } else if ("last".equalsIgnoreCase(conflictStrategy)) {
                    for (int i = sourceFrontmatters.size() - 1; i >= 0; i--) {
                        if (sourceFrontmatters.get(i) != null) {
                            keptFrontmatter = sourceFrontmatters.get(i);
                            keptFrom = sources.get(i).getOriginalFileName();
                            break;
                        }
                    }
                }
            } else if (fmCount == 1) {
                for (int i = 0; i < sourceFrontmatters.size(); i++) {
                    if (sourceFrontmatters.get(i) != null) {
                        keptFrontmatter = sourceFrontmatters.get(i);
                        keptFrom = sources.get(i).getOriginalFileName();
                        break;
                    }
                }
            }

            StringBuilder merged = new StringBuilder();
            if (keptFrontmatter != null) {
                merged.append(keptFrontmatter);
                if (!keptFrontmatter.endsWith("\n")) merged.append("\n");
                merged.append("\n");
            }
            for (int i = 0; i < sources.size(); i++) {
                String name = sources.get(i).getOriginalFileName();
                String content = sourceContents.get(i);
                String fm = sourceFrontmatters.get(i);
                if (fm != null && content.startsWith(fm)) {
                    content = content.substring(fm.length());
                }
                if (prefixHeaders) {
                    merged.append("# ").append(name).append("\n\n");
                } else {
                    merged.append("<!-- merged from ").append(name).append(" -->\n");
                }
                merged.append(content);
                if (i < sources.size() - 1) {
                    merged.append("\n\n");
                }
            }

            String newContent = merged.toString();

            // 用源文件名构造合并后的显示名：如 "A_+_B_merged.md"
            StringBuilder nameBuilder = new StringBuilder();
            for (int i = 0; i < sources.size() && i < 3; i++) {
                if (i > 0) nameBuilder.append("_+_");
                String srcName = sources.get(i).getOriginalFileName();
                int dot = srcName.lastIndexOf('.');
                String base = dot > 0 ? srcName.substring(0, dot) : srcName;
                // 限制每个源名长度，避免总文件名过长
                if (base.length() > 30) base = base.substring(0, 30);
                nameBuilder.append(base);
            }
            nameBuilder.append("_merged.md");
            String displayName = nameBuilder.toString();

            Map<String, Object> saveResult = saveAndReturnResult(newContent, userFile, userId, displayName);

            saveResult.put("sourceFileIds", sourceIdsRaw);
            saveResult.put("totalLines", newContent.split("\n", -1).length);
            saveResult.put("frontmatterKept", keptFrom);
            saveResult.put("sourceCount", sources.size());
            saveResult.put("message", "Merge completed: " + sources.size() + " files merged");
            return FileToolResponse.ok(saveResult, saveResult.get("fileName").toString());
        } catch (Exception e) {
            log.error("md_merge failed", e);
            return FileToolResponse.error("md_merge failed: " + e.getMessage(),
                    userFile != null ? userFile.getOriginalFileName() : "md_merge");
        }
    }
}
