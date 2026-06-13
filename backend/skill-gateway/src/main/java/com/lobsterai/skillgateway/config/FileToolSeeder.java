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
        // 回退方案 B：恢复 6 个细粒度 word_* 工具，删除 word_ops 整合行。
        // 启动时：1) 删除 skills 表里的 word_ops 行（如果存在）；
        //         2) 把 6 个老 word_* 重新 enable（之前 disable 了）；
        //         3) 重新 seed 6 个 word_*（缺哪个补哪个）。
        rollbackWordOpsIntegration();

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

        // ===== MD 扩展操作（5.5 / 模块四 §4）=====
        seedFileOperate("md_images", "提取 Markdown 文件所有图片引用（内联 / 引用式）");
        seedFileOperate("md_headings", "提取 Markdown 文件全层级标题（ATX + Setext）");
        seedFileOperate("md_table", "提取 Markdown GFM 表格（header + rows，含对齐说明符）");
        seedFileOperate("md_list_items", "提取 Markdown 所有列表项（无序 + 有序 + 缩进）");
        seedFileOperate("md_tasks", "提取 Markdown 任务清单项（[ ]/[x]）");
        seedFileOperate("md_emphasis", "提取 Markdown 加粗/斜体/删除线/行内代码（代码块内不解析）");
        seedFileOperate("md_toc", "生成 Markdown 文档目录（嵌套 outline 树，支持跳级）");
        seedFileOperate("md_filter_section",
                "删除或保留 Markdown 文件中指定标题的整节内容，生成新文件（不改原文件）。\n"
                        + "参数说明（二选一，不可同时使用）：\n"
                        + "  • remove: 要删除的标题文本数组——这些标题及其下属整节会被移除，其余内容保留。\n"
                        + "    例：{\"remove\":[\"第二章\"]} 删除 # 第二章 整节\n"
                        + "    例：{\"remove\":[\"安装详情\",\"使用方式\"]} 删除 ### 安装详情 和 ### 使用方式 两节\n"
                        + "  • keep: 要保留的标题文本数组——文档只保留这些标题整节，其余内容全部删除。\n"
                        + "    例：{\"keep\":[\"第三章\"]} 只保留 # 第三章\n"
                        + "    例：{\"keep\":[\"第一章\",\"第二章\"]} 只保留前两章\n"
                        + "  • fileRef: 文件名或文件 ID（如 \"report.md\" 或 \"18\"）\n"
                        + "一节定义为：从该标题行到下一个同级或更高级标题行之前的所有内容。标题匹配区分大小写。",
                mdFilterSectionSchema());
        seedFileOperate("md_merge", "多 Markdown 文件拼接合并（frontmatter 冲突可配置，prefixHeaders 可关闭）",
                mdMergeSchema());
    }

    // ========== 整合方案 B：word_ops 单一入口 ==========

    /** 旧的 6 个 word_* 工具，启动时 disable，避免 LLM 看到重复功能 */
    private static final java.util.Set<String> LEGACY_WORD_TOOLS;
    static {
        // JDK 1.8 兼容：不能使用 Set.of（Java 9+），用 HashSet + Collections.addAll
        java.util.Set<String> s = new java.util.HashSet<String>();
        java.util.Collections.addAll(s,
                "word_read", "word_write", "word_extract_content",
                "word_search_keyword", "word_replace_text", "word_template_fill");
        LEGACY_WORD_TOOLS = java.util.Collections.unmodifiableSet(s);
    }

    /** word_ops 工具的 description（agent-core 透给 LLM） */
    private static final String WORD_OPS_DESCRIPTION =
            "Word 文档操作一体化工具（合并 word_read/word_write/word_extract_content/" +
            "word_search_keyword/word_replace_text/word_template_fill 六个细粒度功能）。\n" +
            "调用时必须先用 action 指定具体子操作：\n" +
            "  • action=read              读取 Word 文档全文（段落 + 全文文本）\n" +
            "  • action=write             创建一个新的 Word 文档（需 title + content）\n" +
            "  • action=extract_content   提取结构化内容（标题大纲 / 表格 / 图片）\n" +
            "  • action=search_keyword    搜索关键字（带上下文匹配结果）\n" +
            "  • action=replace_text      替换文档中的文本（支持全部/首个），需 oldText + newText\n" +
            "  • action=template_fill     用 values 填充 {{placeholder}} 占位符\n" +
            "支持的 fileRef 形式：文件名（如 'report.docx'）或文件 ID（数字）。\n" +
            "写操作（write/replace_text/template_fill）会自动 in-place 覆盖原文件，fileId 不变。\n" +
            "**严禁**使用 'replace' / 'template' / 'fill' / 'search' / 'extract' 等简写 — 必须是上表的完整字符串。";

    /**
     * 把一组老的细粒度 skill 行禁用（enabled=0）。
     * <p>
     * 为什么 disable 而不是删除：用户可能手工编辑过那些行（description/schema），
     * 直接 delete 会丢用户数据；disable 让 LLM 看不到，DB 行保留以便回滚/审计。
     * </p>
     */
    private void disableLegacyFileToolSkills(java.util.Set<String> legacyToolNames) {
        for (String name : legacyToolNames) {
            Skill existing = skillMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                            .eq(Skill::getName, name));
            if (existing == null) {
                continue;  // 首次启动还没建过
            }
            // Skill.enabled 是 primitive boolean，手写 getter 是 isEnabled() 而非 getEnabled()
            if (!existing.isEnabled()) {
                continue;  // 已经是 disabled 状态
            }
            existing.setEnabled(false);
            skillMapper.updateById(existing);
            log.info("Disabled legacy file tool skill: {} (id={})", name, existing.getId());
        }
    }

    /**
     * 回退 word_ops 整合：删除 word_ops 行 + 恢复 6 个细粒度 word_* 工具。
     * <p>
     * 启动时自动执行，可重入。流程：
     * </p>
     * <ol>
     *   <li>删除 skills 表里的 word_ops 行（如果存在）</li>
     *   <li>6 个老 word_* 工具（如果 skills 表里行存在但 enabled=false）→ 重新 enable</li>
     *   <li>调用 {@link #seedFileOperate} 重新 seed 6 个老 word_*（缺哪个补哪个）</li>
     *   <li>对应的 system_skills 行通过 seedSystem 自动 re-seed</li>
     * </ol>
     */
    private void rollbackWordOpsIntegration() {
        // 1. 删除 word_ops 行
        Skill wordOps = skillMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                        .eq(Skill::getName, "word_ops"));
        if (wordOps != null) {
            skillMapper.deleteById(wordOps.getId());
            log.info("Deleted integrated word_ops skill (id={})", wordOps.getId());
        }

        // 2. 6 个老 word_* 重新 enable
        for (String name : LEGACY_WORD_TOOLS) {
            Skill existing = skillMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                            .eq(Skill::getName, name));
            if (existing != null && !existing.isEnabled()) {
                existing.setEnabled(true);
                skillMapper.updateById(existing);
                log.info("Re-enabled legacy word skill: {} (id={})", name, existing.getId());
            }
        }

        // 3. 重新 seed 6 个老 word_*（与昨天方案 B 之前一致）
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
    }

    /**
     * 种子 family 整合型 skill：单一对外 name，内部走 family+action 路由。
     * <p>
     * 与 {@link #seedSkill} 的区别：configuration 是 {@code {kind: "file_tool", family: "word"}}
     * 而不是 {@code {kind: "file_tool", toolName: "word_read"}}。
     * </p>
     * <p>
     * 下游由 {@code SkillExecutionService.executeFileToolSkill} 读 configuration.family
     * 与 parameters.action 拼成内部 toolName（{@code <family>_<action>）委托给现有
     * {@code FileToolService} 调度。
     * </p>
     */
    private void seedFamily(String familyName, String description,
                            Map<String, Map<String, Object>> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);

            Skill existing = skillMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                            .eq(Skill::getName, familyName));
            if (existing != null) {
                String prev = existing.getSchemaPropertiesJson();
                if (prev == null || !prev.equals(schemaJson)) {
                    existing.setSchemaPropertiesJson(schemaJson);
                    existing.setDescription(description);
                    skillMapper.updateById(existing);
                    log.info("Updated family skill schema: {} (id={})", familyName, existing.getId());
                } else {
                    log.debug("Family skill already exists with same schema: {}", familyName);
                }
                return;
            }

            // configuration 关键变化：family（不是 toolName）
            // 决定 SkillExecutionService 走 family+action 路由
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("kind", "file_tool");
            config.put("family", deriveFamily(familyName));  // "word_ops" -> "word"
            String configJson = objectMapper.writeValueAsString(config);

            Skill skill = new Skill();
            skill.setName(familyName);
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
            log.info("Seeded family skill: {} (id={}, family={})",
                    familyName, skill.getId(), deriveFamily(familyName));
        } catch (Exception e) {
            log.error("Failed to seed family skill '{}': {}", familyName, e.getMessage());
        }
    }

    /** "word_ops" -> "word"，"txt_ops" -> "txt"，"file_ops" -> "file" */
    private static String deriveFamily(String familySkillName) {
        int idx = familySkillName.indexOf("_ops");
        return idx > 0 ? familySkillName.substring(0, idx) : familySkillName;
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
            // 构建 schema_properties JSON（每次启动都用最新版）
            String schemaJson = objectMapper.writeValueAsString(schema);

            // 检查是否已存在同名 skill — 已存在则更新 schema（description 和 schema 跟随代码升级）
            Skill existing = skillMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                            .eq(Skill::getName, toolName));
            if (existing != null) {
                String prev = existing.getSchemaPropertiesJson();
                if (prev == null || !prev.equals(schemaJson)) {
                    existing.setSchemaPropertiesJson(schemaJson);
                    existing.setDescription(description);
                    skillMapper.updateById(existing);
                    log.info("Updated existing skill schema: {} (id={})", toolName, existing.getId());
                } else {
                    log.debug("Skill already exists with same schema: {}", toolName);
                }
                return;
            }

            // 构建 configuration JSON
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("kind", "file_tool");
            config.put("toolName", toolName);
            String configJson = objectMapper.writeValueAsString(config);

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
        // 描述中显式包含字段名，避免 LLM 只看语义忽略 key 时漏传
        s.put("oldText", stringProp("oldText：要替换的原文本（必填）", true));
        s.put("newText", stringProp("newText：替换后的新文本（必填）", true));
        Map<String, Object> replaceAll = new LinkedHashMap<>();
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "replaceAll：是否全部替换（默认仅替换第一个）");
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

    // ========== 整合方案 B：word_ops 单一 schema ==========

    /**
     * word_ops 工具的参数 schema。
     * <p>
     * 设计要点：
     * </p>
     * <ol>
     *   <li>action 是 string enum —— 严格拼写 6 个允许值（read/write/extract_content/...）</li>
     *   <li>所有其它参数标记 optional —— 不同 action 用到的参数子集不同，
     *       gateway 端 executeFileToolSkill 按 action 路由到对应 handler，handler 自己校验必填</li>
     *   <li>description 写明"哪个 action 需要哪个参数"，LLM 一次看到全图</li>
     * </ol>
     * <p>
     * 为什么不使用 discriminated union / oneOf：
     * AGENTS.md 5.4 禁止高版本 JS 语法；TypeScript 端用普通 object + zod describe 也行得通。
     * </p>
     */
    private static Map<String, Map<String, Object>> wordOpsSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("description",
                "**必填**。Word 子操作类型，必须是下列之一：\n" +
                "  • read              读取文档全文\n" +
                "  • write             创建一个新文档（需 title + content）\n" +
                "  • extract_content   提取结构化内容（标题/表格/图片）\n" +
                "  • search_keyword    搜索关键字（需 keyword）\n" +
                "  • replace_text      替换文本（需 oldText + newText，可选 replaceAll）\n" +
                "  • template_fill     填充 {{占位符}}（需 values JSON 字符串）\n" +
                "**严禁**简写 — 必须严格使用以上 6 个完整字符串。");
        s.put("action", action);

        s.put("fileRef", stringProp("文件名或文件 ID。除 action=write 外都必填。", false));
        s.put("title", stringProp("[action=write] 文档标题", false));
        s.put("content", stringProp("[action=write] 文档正文（多行字符串）", false));
        s.put("keyword", stringProp("[action=search_keyword] 要搜索的关键字", false));
        s.put("contextChars",
                intProp("[action=search_keyword] 匹配关键字前后各取多少字符作为上下文，默认 50", false));
        s.put("oldText", stringProp("[action=replace_text] 要替换的原文本（**严格 spelling**）", false));
        s.put("newText", stringProp("[action=replace_text] 替换后的新文本", false));

        Map<String, Object> replaceAll = new LinkedHashMap<>();
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "[action=replace_text] 是否替换所有匹配项，默认 true（全部替换）");
        s.put("replaceAll", replaceAll);

        s.put("values", stringProp(
                "[action=template_fill] JSON 字符串，key=占位符名（如 {\"name\":\"张三\",\"date\":\"2026-06-11\"}）", false));

        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("type", "boolean");
        confirmed.put("description", "二次确认标志，写操作类 action 无需手动设置（gateway 自动注入）");
        s.put("confirmed", confirmed);

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

    // ===== MD 扩展操作 Schema =====

    private static Map<String, Map<String, Object>> mdFilterSectionSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("type", "array");
        keep.put("description",
                "要保留的标题文本列表。文档将只保留这些标题及其下属内容，其余全部删除。"
                        + "如 [\"第三章\"] 只保留 # 第三章 下的全部内容。"
                        + "注意：keep 和 remove 二选一，不要同时传入。");
        keep.put("items", stringProp("Markdown 标题文本（精确匹配，区分大小写）", true));
        s.put("keep", keep);
        Map<String, Object> remove = new LinkedHashMap<>();
        remove.put("type", "array");
        remove.put("description",
                "要删除的标题文本列表。这些标题及其下属内容将从文档中删除，其余内容保留。"
                        + "如 [\"第二章\"] 删除 # 第二章 下的全部内容。"
                        + "注意：keep 和 remove 二选一，不要同时传入。");
        remove.put("items", stringProp("Markdown 标题文本（精确匹配，区分大小写）", true));
        s.put("remove", remove);
        return s;
    }

    private static Map<String, Map<String, Object>> mdMergeSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("fileRef", stringProp("文件名或文件 ID", true));
        Map<String, Object> sourceFileIds = new LinkedHashMap<>();
        sourceFileIds.put("type", "array");
        sourceFileIds.put("description", "要合并的源文件 ID 列表（至少 2 个）");
        Map<String, Object> idItem = new LinkedHashMap<>();
        idItem.put("type", "integer");
        idItem.put("description", "user_files 表中的文件 ID");
        sourceFileIds.put("items", idItem);
        s.put("sourceFileIds", sourceFileIds);
        s.put("frontmatterConflict", stringProp("多文件有 frontmatter 时的冲突处理策略：error（报错）/ first（保留第一个）/ last（保留最后一个），默认 error", false));
        Map<String, Object> prefixHeaders = new LinkedHashMap<>();
        prefixHeaders.put("type", "boolean");
        prefixHeaders.put("description", "是否在每个源文件内容前添加 `# 文件名` 标题，默认 true");
        s.put("prefixHeaders", prefixHeaders);
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
