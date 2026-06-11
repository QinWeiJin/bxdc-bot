package com.lobsterai.skillgateway.config;

import com.lobsterai.skillgateway.entity.SystemSkill;
import com.lobsterai.skillgateway.mapper.SystemSkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 文件工具种子数据初始化器。
 * <p>
 * 启动时将所有文件工具注册到 system_skills 表，
 * 使 agent-core 能通过 /api/system-skills/agent 发现这些工具，
 * 并通过 POST /api/system-skills/execute 统一入口调用。
 * </p>
 */
@Component
@Order(1) // 早于其他 runner 执行
public class FileToolSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileToolSeeder.class);

    /** 所有文件工具使用统一 kind，由 SystemSkillService 分派到 FileToolService */
    private static final String KIND = "FILE_TOOL";

    private final SystemSkillMapper systemSkillMapper;

    public FileToolSeeder(SystemSkillMapper systemSkillMapper) {
        this.systemSkillMapper = systemSkillMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        // ===== 文件管理（模块五 / 6.x，FileManageService 完整实现）=====
        seed("file_list", "列出当前用户已上传的所有文件（支持类型/关键词过滤、按上传时间/大小/名称排序、分页）");
        seed("file_delete", "删除指定文件。支持二次确认：首次调用返回确认请求，LLM 引导用户确认后再次调用并设置 confirmed=true 才执行");
        seed("file_clear_all", "清空当前用户的所有文件。支持二次确认：首次调用返回确认请求，LLM 引导用户确认后再次调用并设置 confirmed=true 才执行");
        seed("file_detail", "查看文件详情（名称、大小、类型、上传时间、downloadUrl、parsedSummary 反序列化结果）");

        // ===== Word 操作（5.3）=====
        seed("word_read", "读取 Word（.doc/.docx）文档的全文正文，返回段落列表与全文文本");
        seed("word_write", "创建一个新的 Word 文档（支持标题 + 多行内容）");
        seed("word_extract_content", "提取 Word 文档的结构化内容（标题大纲/表格/图片）");
        seed("word_search_keyword", "在 Word 文档中搜索关键字，返回带上下文的匹配结果");
        seed("word_replace_text", "替换 Word 文档中的文本（支持全部替换或仅替换第一个）");
        seed("word_template_fill", "用 values 填充 Word 文档中的 {{placeholder}} 占位符");

        // ===== TXT/MD 操作（5.4）=====
        seed("txt_read", "读取 TXT/MD 文本文件（支持指定编码、行范围）");
        seed("txt_write", "写入 TXT/MD 文本文件（覆盖或追加，可指定编码）");
        seed("txt_keyword_lines", "提取包含关键词的所有行（可选上下文行）");
        seed("txt_regex", "用正则表达式匹配文本行，返回捕获组");
        seed("txt_line_range", "提取指定行范围（1-based）");
        seed("txt_section", "提取 Markdown 标题章节（支持嵌套控制）");
        seed("txt_stats", "统计字符数/词数/行数/字节数");
        seed("txt_distinct_lines", "去重行（保留首次出现顺序，可选写回）");
        seed("txt_sort_lines", "排序行（字典序或数字序，升序/降序，可选写回）");
        seed("txt_keyword_freq", "统计关键词在文本中的出现频率");
    }

    private void seed(String toolName, String description) {
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
}
