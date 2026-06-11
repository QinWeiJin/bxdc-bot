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
        seed("file_list", "列出当前用户已上传的所有文件（文件名、大小、类型、上传时间、下载链接）");
        seed("file_delete", "删除指定文件。需要用户确认文件名后才会执行删除");
        seed("file_clear_all", "清空当前用户的所有已上传文件。需要用户确认后才会执行");
        seed("file_detail", "查看指定文件的详细信息（名称、大小、类型、上传时间、解析摘要）");
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
