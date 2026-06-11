package com.lobsterai.skillgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.entity.SkillVisibility;
import com.lobsterai.skillgateway.entity.SystemSkill;
import com.lobsterai.skillgateway.mapper.SkillMapper;
import com.lobsterai.skillgateway.mapper.SystemSkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件工具种子数据初始化器。
 * <p>
 * 启动时将文件工具同时注册到：
 * <ul>
 *   <li>{@code system_skills} 表 — 供 {@code POST /api/system-skills/execute} 直连调用</li>
 *   <li>{@code skills} 表 — 供 agent-core 通过 {@code GET /api/skills} 动态发现
 *       （type=EXTENSION），执行走 {@code POST /api/skills/execute} 统一入口</li>
 * </ul>
 * </p>
 */
@Component
@Order(1)
public class FileToolSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileToolSeeder.class);

    private static final String KIND = "FILE_TOOL";
    private static final String SKILL_TYPE = "EXTENSION";
    private static final String CREATED_BY = "public";

    private final SystemSkillMapper systemSkillMapper;
    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;

    public FileToolSeeder(SystemSkillMapper systemSkillMapper,
                          SkillMapper skillMapper,
                          ObjectMapper objectMapper) {
        this.systemSkillMapper = systemSkillMapper;
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        // ===== 文件管理（模块五 / 6.x）=====
        seedFileManage("file_list", "列出当前用户已上传的所有文件（支持类型/关键词过滤、按上传时间/大小/名称排序、分页）",
                fileListSchema());
        seedFileManage("file_delete", "删除指定文件。通过 fileRef 指定文件名或 ID。支持二次确认：首次调用返回确认请求，LLM 引导用户确认后再次调用并设置 confirmed=true 才执行",
                fileDeleteSchema());
        seedFileManage("file_clear_all", "清空当前用户的所有文件。支持二次确认：首次调用返回确认请求，LLM 引导用户确认后再次调用并设置 confirmed=true 才执行",
                confirmedOnlySchema());
        seedFileManage("file_detail", "查看文件详情（名称、大小、类型、上传时间、downloadUrl、parsedSummary 反序列化结果）",
                fileRefSchema());

        // ===== Word 操作（5.3）=====
        seedFileOperate("word_read", "读取 Word（.doc/.docx）文档的全文正文，返回段落列表与全文文本");
        seedFileOperate("word_write", "创建一个新的 Word 文档（支持标题 + 多行内容），参数：title（必填）、content（必填）",
                wordWriteSchema());
        seedFileOperate("word_extract_content", "提取 Word 文档的结构化内容（标题大纲/表格/图片）");
        seedFileOperate("word_search_keyword", "在 Word 文档中搜索关键字，返回带上下文的匹配结果",
                keywordSearchSchema());
        seedFileOperate("word_replace_text", "替换 Word 文档中的文本（支持全部替换或仅替换第一个）",
                replaceTextSchema());
        seedFileOperate("word_template_fill", "用 values 填充 Word 文档中的 {{placeholder}} 占位符",
                templateFillSchema());

        // ===== TXT/MD 操作（5.4）=====
        seedFileOperate("txt_read", "读取 TXT/MD 文本文件（支持指定编码、行范围）", txtReadSchema());
        seedFileOperate("txt_write", "写入 TXT/MD 文本文件（覆盖或追加，可指定编码）", txtWriteSchema());
        seedFileOperate("txt_keyword_lines", "提取包含关键词的所有行（可选上下文行）", txtKeywordLinesSchema());
        seedFileOperate("txt_regex", "用正则表达式匹配文本行，返回捕获组", txtRegexSchema());
        seedFileOperate("txt_line_range", "提取指定行范围（1-based）", txtLineRangeSchema());
        seedFileOperate("txt_section", "提取 Markdown 标题章节（支持嵌套控制）", txtSectionSchema());
        seedFileOperate("txt_stats", "统计字符数/词数/行数/字节数");
        seedFileOperate("txt_distinct_lines", "去重行（保留首次出现顺序，可选写回）", txtDistinctLinesSchema());
        seedFileOperate("txt_sort_lines", "排序行（字典序或数字序，升序/降序，可选写回）", txtSortLinesSchema());
        seedFileOperate("txt_keyword_freq", "统计关键词在文本中的出现频率", txtKeywordFreqSchema());
    }

    // ========== 种子方法 ==========

    /** 种子 system_skills 表 */
    private void seedSystem(String toolName, String description) {
        try {
            if (systemSkillMapper.findByToolName(toolName).isPresent()) {
                log.debug("System skill already exists: {}", toolName);
                return;
            }
            SystemSkill skill = new SystemSkill();
            skill.setToolName(toolName);
            skill.setDescription(description);
            skill.setKind(KIND);
            skill.setEnabled(true);
            skill.setSchemaVersion(1);
            systemSkillMapper.insert(skill);
            log.info("Seeded system skill: {} (kind={})", toolName, KIND);
        } catch (Exception e) {
            log.error("Failed to seed system skill '{}': {}", toolName, e.getMessage());
        }
    }

    /** 种子 skills 表 + system_skills 表：文件管理类（file_list / file_delete / file_clear_all / file_detail） */
    private void seedFileManage(String toolName, String description,
                                Map<String, Map<String, Object>> schema) {
        seedSystem(toolName, description);
        seedSkill(toolName, description, schema);
    }

    /** 种子 skills 表 + system_skills 表：文件操作类（word_* / txt_* / md_*） */
    private void seedFileOperate(String toolName, String description) {
        seedFileOperate(toolName, description, fileRefSchema());
    }

    private void seedFileOperate(String toolName, String description,
                                 Map<String, Map<String, Object>> schema) {
        seedSystem(toolName, description);
        seedSkill(toolName, description, schema);
    }

    /** 将一条文件工具注册到 skills 表 */
    @SuppressWarnings("unchecked")
    private void seedSkill(String toolName, String description,
                           Map<String, Map<String, Object>> schema) {
        try {
            // 检查是否已存在同名 skill
            Skill existing = skillMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                            .eq(Skill::getName, toolName));
            if (existing != null) {
                log.debug("Skill already exists: {}", toolName);
                return;
            }

            // 构建 configuration JSON
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("kind", "file_tool");
            config.put("toolName", toolName);
            String configJson = objectMapper.writeValueAsString(config);

            // 构建 schema_properties JSON
            String schemaJson = objectMapper.writeValueAsString(schema);

            Skill skill = new Skill();
            skill.setName(toolName);
            skill.setDescription(description);
            skill.setType(SKILL_TYPE);
            skill.setConfiguration(configJson);
            skill.setExecutionMode("CONFIG");
            skill.setEnabled(true);
            skill.setRequiresConfirmation(false);
            skill.setVisibility(SkillVisibility.PUBLIC);
            skill.setCreatedBy(CREATED_BY);
            skill.setSchemaPropertiesJson(schemaJson);

            skillMapper.insert(skill);
            log.info("Seeded skill: {} (id={}, type={}, kind=file_tool)", toolName, skill.getId(), SKILL_TYPE);
        } catch (Exception e) {
            log.error("Failed to seed skill '{}': {}", toolName, e.getMessage());
        }
    }

    // ========== Schema 定义 ==========

    private static Map<String, Map<String, Object>> fileListSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileType", stringProp("按文件类型过滤（如 docx/xlsx/md），不区分大小写", false));
        s.put("keyword", stringProp("按文件名模糊匹配，不区分大小写", false));
        s.put("sortBy", stringProp("排序字段：uploadTime/size/name，默认 uploadTime", false));
        s.put("order", stringProp("排序方向：asc/desc，默认 desc", false));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("type", "integer");
        page.put("description", "页码（1-based），默认 1");
        s.put("page", page);
        Map<String, Object> pageSize = new LinkedHashMap<>();
        pageSize.put("type", "integer");
        pageSize.put("description", "每页数量，默认 50，最大 200");
        s.put("pageSize", pageSize);
        return s;
    }

    private static Map<String, Map<String, Object>> fileRefSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID，用于指定要操作的文件", true));
        return s;
    }

    private static Map<String, Map<String, Object>> fileDeleteSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("要删除的文件名或文件 ID", true));
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("type", "boolean");
        confirmed.put("description", "二次确认标志。LLM 应先在对话中引导用户确认，用户确认后再次调用设置 confirmed=true");
        s.put("confirmed", confirmed);
        return s;
    }

    private static Map<String, Map<String, Object>> confirmedOnlySchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("type", "boolean");
        confirmed.put("description", "二次确认标志。首次调用不传，LLM 引导用户确认文件名后再次调用时设置 confirmed=true");
        s.put("confirmed", confirmed);
        return s;
    }

    private static Map<String, Map<String, Object>> wordWriteSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("title", stringProp("Word 文档标题", true));
        s.put("content", stringProp("Word 文档正文内容（多行文本）", true));
        return s;
    }

    private static Map<String, Map<String, Object>> keywordSearchSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("keyword", stringProp("搜索关键字", true));
        return s;
    }

    private static Map<String, Map<String, Object>> replaceTextSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("oldText", stringProp("要替换的原文本", true));
        s.put("newText", stringProp("替换后的新文本", true));
        Map<String, Object> replaceAll = new LinkedHashMap<>();
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "是否全部替换（默认仅替换第一个）");
        s.put("replaceAll", replaceAll);
        return s;
    }

    private static Map<String, Map<String, Object>> templateFillSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", "object");
        values.put("description", "占位符键值对，如 {\"name\": \"张三\", \"date\": \"2026-01-01\"}");
        s.put("values", values);
        return s;
    }

    private static Map<String, Map<String, Object>> txtReadSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("encoding", stringProp("文件编码（如 UTF-8/GBK），默认 UTF-8", false));
        s.put("startLine", intProp("起始行号（1-based），默认 1", false));
        s.put("endLine", intProp("结束行号（1-based），默认文件末尾", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtWriteSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("content", stringProp("要写入的文本内容", true));
        s.put("encoding", stringProp("文件编码（如 UTF-8/GBK），默认 UTF-8", false));
        Map<String, Object> append = new LinkedHashMap<>();
        append.put("type", "boolean");
        append.put("description", "是否追加模式（false=覆盖，默认覆盖）");
        s.put("append", append);
        return s;
    }

    private static Map<String, Map<String, Object>> txtKeywordLinesSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("keyword", stringProp("要搜索的关键词", true));
        s.put("contextLines", intProp("上下文行数（匹配行前后各 N 行），默认 0", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtRegexSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("pattern", stringProp("正则表达式", true));
        s.put("groupIndex", intProp("捕获组索引（0=完整匹配），默认 0", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtLineRangeSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("startLine", intProp("起始行号（1-based），默认 1", false));
        s.put("endLine", intProp("结束行号（1-based），默认文件末尾", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtSectionSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("heading", stringProp("Markdown 标题文本（如 \"## 概述\"）", true));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("type", "boolean");
        nested.put("description", "是否包含子标题");
        s.put("nested", nested);
        return s;
    }

    private static Map<String, Map<String, Object>> txtDistinctLinesSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        Map<String, Object> writeBack = new LinkedHashMap<>();
        writeBack.put("type", "boolean");
        writeBack.put("description", "是否将去重结果写回文件");
        s.put("writeBack", writeBack);
        return s;
    }

    private static Map<String, Map<String, Object>> txtSortLinesSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("mode", stringProp("排序模式：lex（字典序）或 num（数字序），默认 lex", false));
        s.put("order", stringProp("排序方向：asc/desc，默认 asc", false));
        Map<String, Object> writeBack = new LinkedHashMap<>();
        writeBack.put("type", "boolean");
        writeBack.put("description", "是否将排序结果写回文件");
        s.put("writeBack", writeBack);
        return s;
    }

    private static Map<String, Map<String, Object>> txtKeywordFreqSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        s.put("keyword", stringProp("要统计的关键词（多个用逗号分隔）", true));
        return s;
    }

    // ========== Schema 构建工具方法 ==========

    private static Map<String, Object> stringProp(String description, boolean required) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", description);
        if (required) {
            prop.put("required", true);
        }
        return prop;
    }

    private static Map<String, Object> intProp(String description, boolean required) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "integer");
        prop.put("description", description);
        if (required) {
            prop.put("required", true);
        }
        return prop;
    }
}
