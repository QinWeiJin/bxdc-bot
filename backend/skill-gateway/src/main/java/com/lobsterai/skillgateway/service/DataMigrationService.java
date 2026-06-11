package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.entity.Conversation;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.entity.SystemSkill;
import com.lobsterai.skillgateway.entity.User;
import com.lobsterai.skillgateway.mapper.ConversationMapper;
import com.lobsterai.skillgateway.mapper.SkillMapper;
import com.lobsterai.skillgateway.mapper.SystemSkillMapper;
import com.lobsterai.skillgateway.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 投产数据迁移服务。
 * <p>
 * Spring Boot 启动时自动执行，为存量用户创建默认对话并归属全量可用 Skill。
 * 幂等安全：已迁移的用户跳过。
 * 失败不阻塞应用启动。
 * </p>
 */
@Component
public class DataMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationService.class);

    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final SkillMapper skillMapper;
    private final SystemSkillMapper systemSkillMapper;

    public DataMigrationService(UserMapper userMapper,
                                ConversationMapper conversationMapper,
                                SkillMapper skillMapper,
                                SystemSkillMapper systemSkillMapper) {
        this.userMapper = userMapper;
        this.conversationMapper = conversationMapper;
        this.skillMapper = skillMapper;
        this.systemSkillMapper = systemSkillMapper;
    }

    @PostConstruct
    public void migrateExistingUsersToDefaultConversation() {
        try {
            doMigrate();
        } catch (Exception e) {
            log.error("[DataMigration] Migration failed: {}", e.getMessage(), e);
            // Do NOT block application startup
        }
    }

    private void doMigrate() {
        // 1. Get all users
        List<User> users = userMapper.selectList(null);
        int totalUsers = users.size();
        int migratedCount = 0;

        // 2. Get all enabled Skill IDs (Extension + Built-in)
        List<Long> allSkillIds = getAllEnabledSkillIds();

        log.info("[DataMigration] Starting: {} users, {} enabled skills available",
                totalUsers, allSkillIds.size());

        for (User user : users) {
            // Check if user already has at least one conversation
            List<Conversation> existing = conversationMapper.selectByUserIdOrderByUpdatedAt(user.getId());
            if (!existing.isEmpty()) {
                continue; // Already migrated, skip
            }

            // Create default conversation
            Conversation conv = new Conversation();
            conv.setConversationId(UUID.randomUUID().toString());
            conv.setUserId(user.getId());
            conv.setName("默认对话");
            conv.setEnabledSkills(skillIdsToJson(allSkillIds));
            conv.setStatus("active");
            conv.setCreatedAt(LocalDateTime.now());
            conv.setUpdatedAt(LocalDateTime.now());

            conversationMapper.insert(conv);
            migratedCount++;
            log.info("[DataMigration] Created default conversation for user: {}", user.getId());
        }

        log.info("[DataMigration] Complete: {} total users, {} created default conversations",
                totalUsers, migratedCount);
    }

    private List<Long> getAllEnabledSkillIds() {
        // Extension skills (skills table) where enabled = 1
        List<Long> extIds = skillMapper.selectList(null).stream()
                .filter(Skill::isEnabled)
                .map(Skill::getId)
                .collect(Collectors.toList());

        // Built-in skills (system_skills table) where enabled = 1
        List<Long> builtinIds = systemSkillMapper.findByEnabledIsTrueOrderByToolNameAsc().stream()
                .map(SystemSkill::getId)
                .collect(Collectors.toList());

        return Stream.concat(extIds.stream(), builtinIds.stream())
                .distinct()
                .collect(Collectors.toList());
    }

    private String skillIdsToJson(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
