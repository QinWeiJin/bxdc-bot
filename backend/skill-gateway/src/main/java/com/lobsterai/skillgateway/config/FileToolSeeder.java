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

        // ===== Excel 操作（支持 xlsx/xls/csv）=====
        seedFileOperate("excel_read", "读取 Excel 文件内容，返回表头和数据行。支持分页返回，默认最多返回 100 行。返回结果包含 headers（列头列表）和 rows（数据行列表）。", excelReadSchema());
        seedFileOperate("excel_write", "创建或覆盖 Excel 文件。两种场景：1) 传入 fileId 时在临时文件基础上写入数据；2) 不传 fileId 时根据 headers 和 rows 创建全新文件，自动上传到 FTP 并插入 userfile 表，返回新的 fileId 供后续操作使用。需提供 headers（列头数组，必填）和 rows（数据行数组）。", excelWriteSchema());
        seedFileOperate("excel_init_temp", "初始化临时文件：根据原文件生成临时文件副本，上传到 FTP 并返回文件信息。此工具用于多步数据处理场景，首次操作前需调用此工具创建临时文件，后续所有 Excel 操作都在此临时文件上进行。返回结果包含 fileId（文件 ID，作为后续工具调用的入参）、fileName（临时文件名）、filePath（FTP 下载路径）和 headers（列头信息）。", fileRefSchema());
        seedFileOperate("excel_filter", "根据条件筛选数据行。支持多种操作符：equals（等于）、contains（包含）、gt（大于）、lt（小于）、gte（大于等于）、lte（小于等于）、notEquals（不等于）。筛选结果写回临时文件，返回更新后的文件信息。", excelFilterSchema());
        seedFileOperate("excel_sort", "根据指定列对数据进行排序。支持升序（asc）和降序（desc）两种排序方向。排序结果写回临时文件，返回更新后的文件信息。", excelSortSchema());
        seedFileOperate("excel_aggregate", "按指定列分组并进行聚合统计。支持 sum（求和）、avg（平均值）、count（计数）、min（最小值）、max（最大值）五种聚合类型。聚合结果写回临时文件，返回更新后的文件信息。", excelAggregateSchema());
        seedFileOperate("excel_pivot", "透视分析：按行维度和列维度进行交叉汇总。需指定 rowDimension（行维度列）、colDimension（列维度列）和 valueColumn（值列）。透视结果写回临时文件，返回更新后的文件信息。", excelPivotSchema());
        seedFileOperate("excel_calculate", "列运算：基于现有列生成新计算列。通过 formula 参数指定计算公式，支持用 {列名} 引用其他列，例如 {销售额} * {数量} 或 {单价} * 1.1。计算结果作为新列添加到数据中，写回临时文件。", excelCalculateSchema());
        seedFileOperate("excel_select_columns", "选择指定列，删除其他列。需提供 columns 数组指定要保留的列名。选择结果写回临时文件，返回更新后的文件信息。", excelSelectColumnsSchema());
        seedFileOperate("excel_clean", "数据清洗操作。支持三种清洗类型：trim（去除字符串首尾空格）、deduplicate（去除重复行）、removeEmpty（移除空行）。清洗结果写回临时文件，返回更新后的文件信息。", excelCleanSchema());
        seedFileOperate("excel_convert_format", "格式转换：将 Excel 文件转换为其他格式。支持 xlsx、xls、csv 三种格式之间的转换。转换结果写回临时文件，返回更新后的文件信息。", excelConvertFormatSchema());
        seedFileOperate("excel_validate", "合规校验：根据指定规则对数据进行校验。支持多种校验规则，如必填校验、数值范围校验、格式校验等。校验结果以 JSON 格式返回，不修改原文件。", excelValidateSchema());
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
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息进行下载等操作");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选，当 fileId 无法获取时使用）", false));
        return s;
    }

    private static Map<String, Map<String, Object>> fileDeleteSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），要删除的文件 ID");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("要删除的文件名（可选）", false));
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
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("keyword", stringProp("搜索关键字", true));
        return s;
    }

    private static Map<String, Map<String, Object>> replaceTextSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
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
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", "object");
        values.put("description", "占位符键值对，如 {\"name\": \"张三\", \"date\": \"2026-01-01\"}");
        s.put("values", values);
        return s;
    }

    private static Map<String, Map<String, Object>> txtReadSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（优先使用），通过文件 ID 直接查询文件信息");
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名或文件 ID（fileId 优先）", false));
        s.put("encoding", stringProp("文件编码（如 UTF-8/GBK），默认 UTF-8", false));
        s.put("startLine", intProp("起始行号（1-based），默认 1", false));
        s.put("endLine", intProp("结束行号（1-based），默认文件末尾", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtWriteSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（优先使用），通过文件 ID 直接查询文件信息");
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名或文件 ID（fileId 优先）", false));
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
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（优先使用），通过文件 ID 直接查询文件信息");
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名或文件 ID（fileId 优先）", false));
        s.put("keyword", stringProp("要搜索的关键词", true));
        s.put("contextLines", intProp("上下文行数（匹配行前后各 N 行），默认 0", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtRegexSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（优先使用），通过文件 ID 直接查询文件信息");
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名或文件 ID（fileId 优先）", false));
        s.put("pattern", stringProp("正则表达式", true));
        s.put("groupIndex", intProp("捕获组索引（0=完整匹配），默认 0", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtLineRangeSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（优先使用），通过文件 ID 直接查询文件信息");
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名或文件 ID（fileId 优先）", false));
        s.put("startLine", intProp("起始行号（1-based），默认 1", false));
        s.put("endLine", intProp("结束行号（1-based），默认文件末尾", false));
        return s;
    }

    private static Map<String, Map<String, Object>> txtSectionSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
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
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        Map<String, Object> writeBack = new LinkedHashMap<>();
        writeBack.put("type", "boolean");
        writeBack.put("description", "是否将去重结果写回文件");
        s.put("writeBack", writeBack);
        return s;
    }

    private static Map<String, Map<String, Object>> txtSortLinesSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
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
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("keyword", stringProp("要统计的关键词（多个用逗号分隔）", true));
        return s;
    }

    // ========== Excel 工具 Schema 定义 ==========

    private static Map<String, Map<String, Object>> excelReadSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("maxRows", intProp("最大返回行数，默认 100", false));
        return s;
    }

    private static Map<String, Map<String, Object>> excelWriteSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（可选）。两种场景：1) 传入 fileId 时在临时文件基础上操作；2) 不传 fileId 时创建新文件并插入 userfile 表");
        fileId.put("required", false);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("type", "array");
        headers.put("description", "列头列表（必填），如 [\"姓名\", \"年龄\", \"部门\"]");
        headers.put("required", true);
        s.put("headers", headers);
        
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("type", "array");
        rows.put("description", "数据行列表，如 [[\"张三\", 25, \"研发部\"], [\"李四\", 30, \"销售部\"]]");
        s.put("rows", rows);
        
        return s;
    }

    private static Map<String, Map<String, Object>> excelFilterSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("column", stringProp("列名（必填）", true));
        s.put("operator", stringProp("操作符：equals/contains/gt/lt/gte/lte/notEquals，默认 equals", false));
        s.put("value", stringProp("筛选值（必填）", true));
        return s;
    }

    private static Map<String, Map<String, Object>> excelSortSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("column", stringProp("排序列名（必填）", true));
        s.put("order", stringProp("排序方向：asc/desc，默认 asc", false));
        return s;
    }

    private static Map<String, Map<String, Object>> excelAggregateSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("groupBy", stringProp("分组列名（必填）", true));
        s.put("aggColumn", stringProp("聚合列名（必填）", true));
        s.put("aggType", stringProp("聚合类型：sum/avg/count/min/max，默认 sum", false));
        return s;
    }

    private static Map<String, Map<String, Object>> excelPivotSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("rowDimension", stringProp("行维度（必填）", true));
        s.put("colDimension", stringProp("列维度（必填）", true));
        s.put("valueColumn", stringProp("值列（必填）", true));
        return s;
    }

    private static Map<String, Map<String, Object>> excelCalculateSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("newColumn", stringProp("新列名（必填）", true));
        s.put("formula", stringProp("计算公式，支持引用列名，如 {col1} + {col2} * 1.1", true));
        return s;
    }

    private static Map<String, Map<String, Object>> excelSelectColumnsSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("type", "array");
        columns.put("description", "要选择的列名列表（必填），如 [\"姓名\", \"年龄\"]");
        columns.put("required", true);
        s.put("columns", columns);
        
        return s;
    }

    private static Map<String, Map<String, Object>> excelCleanSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("cleanType", stringProp("清洗类型：trim（去除首尾空格）/deduplicate（去重）/removeEmpty（移除空行）", true));
        return s;
    }

    private static Map<String, Map<String, Object>> excelConvertFormatSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        s.put("targetFormat", stringProp("目标格式：xlsx/xls/csv", true));
        return s;
    }

    private static Map<String, Map<String, Object>> excelValidateSchema() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        Map<String, Object> fileId = new LinkedHashMap<>();
        fileId.put("type", "integer");
        fileId.put("description", "文件 ID（必填），通过文件 ID 直接查询文件信息");
        fileId.put("required", true);
        s.put("fileId", fileId);
        s.put("fileRef", stringProp("文件名（可选）", false));
        
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("type", "array");
        rules.put("description", "校验规则列表，如 [{\"column\": \"年龄\", \"rule\": \"min\", \"value\": 18}]");
        s.put("rules", rules);
        
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
