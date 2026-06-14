package com.lobsterai.skillgateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * 数据库轻量级 schema 迁移器。
 *
 * Spring 的 schema-mysql.sql 使用 CREATE TABLE IF NOT EXISTS，
 * 不会给已存在的表添加新列。本组件在 DataSource 就绪后立即执行迁移，
 * 确保定时任务调度器启动前列已就绪。
 *
 * 实现 InitializingBean 是在 afterPropertiesSet() 阶段，
 * 比 ApplicationRunner 更早，且 DataSource 已就绪。
 *
 * 重复执行是幂等的，不引入第三方包（不替换 Flyway/Liquibase）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrationRunner implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    private final DataSource dataSource;

    public SchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            migrateAsyncTasks(conn);
            migrateSkills(conn);
            migrateUserFiles(conn);
            migrateConversationApiColumns(conn);
            migrateAsyncTaskChatReply(conn);
            migrateConversationMessageSummaries(conn);
        } catch (Exception e) {
            // 迁移失败不阻塞应用启动，但记录严重警告
            log.warn("[SchemaMigration] Migration failed: {}", e.getMessage());
        }
    }

    private void migrateAsyncTasks(Connection conn) {
        String table = "async_tasks";
        if (!tableExists(conn, table)) {
            log.debug("[SchemaMigration] Table {} does not exist yet (will be created by schema-mysql.sql)", table);
            return;
        }

        Set<String> existingColumns = getColumnNames(conn, table);
        Set<String> existingIndexes = getIndexNames(conn, table);

        // 1. notified_at 列
        ensureColumn(conn, table, "notified_at", existingColumns,
                "ALTER TABLE async_tasks ADD COLUMN notified_at DATETIME DEFAULT NULL " +
                "COMMENT '用户已读时间；NULL 表示尚未读'");

        // 2. idx_async_user_unread 索引
        ensureIndex(conn, table, "idx_async_user_unread", existingIndexes,
                "ALTER TABLE async_tasks ADD INDEX idx_async_user_unread (user_id, status, notified_at)");

        // 3. poll_strategy 列（原子 2：SINGLE_CALL 兼容）
        ensureColumn(conn, table, "poll_strategy", existingColumns,
                "ALTER TABLE async_tasks ADD COLUMN poll_strategy VARCHAR(20) DEFAULT 'PERIODIC' " +
                "COMMENT 'PERIODIC=周期轮询；SINGLE_CALL=单次长调用（无 pollEndpoint，靠 HTTP 长 readTimeout 等结果）'");

        // 4. single_call_read_timeout_seconds 列（原子 2）
        ensureColumn(conn, table, "single_call_read_timeout_seconds", existingColumns,
                "ALTER TABLE async_tasks ADD COLUMN single_call_read_timeout_seconds INT DEFAULT NULL " +
                "COMMENT 'SINGLE_CALL 模式专用 read timeout（秒）；NULL 时回退到 maxWaitSeconds'");

        // 5. request_signature 列（原子 3：去重）
        ensureColumn(conn, table, "request_signature", existingColumns,
                "ALTER TABLE async_tasks ADD COLUMN request_signature VARCHAR(64) DEFAULT NULL " +
                "COMMENT '请求签名 SHA-256 hex（去重用）'");

        // 6. idx_async_user_session_sig_time 索引（原子 3）
        ensureIndex(conn, table, "idx_async_user_session_sig_time", existingIndexes,
                "ALTER TABLE async_tasks ADD INDEX idx_async_user_session_sig_time (user_id, session_id, request_signature, created_at)");

        // 7. request_body 列（SINGLE_CALL 兼容：scheduler 发起长调用需要原始 body）
        ensureColumn(conn, table, "request_body", existingColumns,
                "ALTER TABLE async_tasks ADD COLUMN request_body MEDIUMTEXT DEFAULT NULL " +
                "COMMENT 'SINGLE_CALL 模式的原始请求体（JSON 字符串）；PERIODIC 模式为 NULL'");
    }

    /**
     * skills 表：lskrat 在 3479f7cb 加了 schema_properties 字段但没改 SQL。
     * 之前 6-08 后启动会报 "Unknown column 'schema_properties' in 'field list'"。
     * 这里补上 ALTER（已存在则跳过，幂等）。
     */
    private void migrateSkills(Connection conn) {
        String table = "skills";
        if (!tableExists(conn, table)) {
            log.debug("[SchemaMigration] Table {} does not exist yet (will be created by schema-mysql.sql)", table);
            return;
        }

        Set<String> existingColumns = getColumnNames(conn, table);

        // schema_properties：lskrat 6-08 在 Skill 实体加的 @TableField，SQL 未同步
        ensureColumn(conn, table, "schema_properties", existingColumns,
                "ALTER TABLE skills ADD COLUMN schema_properties TEXT DEFAULT NULL " +
                "COMMENT 'Skill 实体持久化的 JSON schema 配置（lskrat 6-08 加，未同步 SQL）'");
    }

    /**
     * user_files 表：智能文件中心预留字段（按需启用）。
     * session_id / conversation_id 关联 agent-core 调用工具时的会话和对话，
     * 可空（老数据不填），未来按 session / conversation 维度查询附件。
     */
    private void migrateUserFiles(Connection conn) {
        String table = "user_files";
        if (!tableExists(conn, table)) {
            log.debug("[SchemaMigration] Table {} does not exist yet (will be created by schema-mysql.sql)", table);
            return;
        }

        Set<String> existingColumns = getColumnNames(conn, table);
        Set<String> existingIndexes = getIndexNames(conn, table);

        // 1. session_id 列
        ensureColumn(conn, table, "session_id", existingColumns,
                "ALTER TABLE user_files ADD COLUMN session_id VARCHAR(128) DEFAULT NULL " +
                "COMMENT '预留：关联会话 ID'");

        // 2. conversation_id 列
        ensureColumn(conn, table, "conversation_id", existingColumns,
                "ALTER TABLE user_files ADD COLUMN conversation_id VARCHAR(128) DEFAULT NULL " +
                "COMMENT '预留：关联对话 ID'");

        // 3. idx_user_files_session 索引
        ensureIndex(conn, table, "idx_user_files_session", existingIndexes,
                "ALTER TABLE user_files ADD INDEX idx_user_files_session (session_id)");

        // 4. idx_user_files_conversation 索引
        ensureIndex(conn, table, "idx_user_files_conversation", existingIndexes,
                "ALTER TABLE user_files ADD INDEX idx_user_files_conversation (conversation_id)");
    }

    private void migrateConversationApiColumns(Connection conn) {
        // ===== conversations 表 =====
        String convTable = "conversations";
        if (!tableExists(conn, convTable)) {
            log.debug("[SchemaMigration] Table {} does not exist yet (will be created by schema-mysql.sql)", convTable);
            return;
        }

        Set<String> convColumns = getColumnNames(conn, convTable);
        Set<String> convIndexes = getIndexNames(conn, convTable);

        // 1. is_published
        ensureColumn(conn, convTable, "is_published", convColumns,
                "ALTER TABLE conversations ADD COLUMN is_published TINYINT(1) NOT NULL DEFAULT 0 " +
                "COMMENT '是否已发布为API: 0=未发布, 1=已发布'");

        // 2. api_description
        ensureColumn(conn, convTable, "api_description", convColumns,
                "ALTER TABLE conversations ADD COLUMN api_description TEXT NULL " +
                "COMMENT 'API描述文本，发布时填写，作为LLM对话上下文的系统消息'");

        // 3. api_key
        ensureColumn(conn, convTable, "api_key", convColumns,
                "ALTER TABLE conversations ADD COLUMN api_key VARCHAR(64) NULL " +
                "COMMENT 'API调用密钥明文'");

        // 4. api_key_hash
        ensureColumn(conn, convTable, "api_key_hash", convColumns,
                "ALTER TABLE conversations ADD COLUMN api_key_hash VARCHAR(64) NULL " +
                "COMMENT 'API调用密钥SHA-256哈希'");

        // 5. idx_api_key_hash 唯一索引
        ensureIndex(conn, convTable, "idx_api_key_hash", convIndexes,
                "CREATE UNIQUE INDEX idx_api_key_hash ON conversations(api_key_hash)");

        // ===== conversation_messages 表 =====
        String msgTable = "conversation_messages";
        if (!tableExists(conn, msgTable)) {
            log.debug("[SchemaMigration] Table {} does not exist yet", msgTable);
            return;
        }

        Set<String> msgColumns = getColumnNames(conn, msgTable);

        // 6. source 列
        ensureColumn(conn, msgTable, "source", msgColumns,
                "ALTER TABLE conversation_messages ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'web' " +
                "COMMENT '消息来源: web=网页端, api=API调用'");
    }

    private boolean tableExists(Connection conn, String table) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to check table existence for {}: {}", table, e.getMessage());
        }
        return false;
    }

    private Set<String> getColumnNames(Connection conn, String table) {
        Set<String> cols = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1).toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to read columns for {}: {}", table, e.getMessage());
        }
        return cols;
    }

    private Set<String> getIndexNames(Connection conn, String table) {
        Set<String> idx = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    idx.add(rs.getString(1).toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to read indexes for {}: {}", table, e.getMessage());
        }
        return idx;
    }

    private void ensureColumn(Connection conn, String table, String column,
                              Set<String> existing, String alterSql) {
        if (existing.contains(column.toLowerCase())) {
            log.debug("[SchemaMigration] Column {}.{} already exists, skip", table, column);
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(alterSql);
            log.info("[SchemaMigration] ✅ Added column {}.{}", table, column);
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to add column {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * async-task-result-echo-to-chat change 配套 schema 迁移（open spec）。
     *
     * 任务：扩展 conversation_messages 表，让它能存"异步任务结果"消息 + LLM 续答总结。
     *
     * - source VARCHAR(10) → VARCHAR(20)：容纳 'ASYNC_TASK_RESULT'（17 字符）
     * - 新增 async_task_id / summary_pending / summary_text / summary_generated_at 列
     * - 新增 async_task_id 索引（查"该任务产生了哪些对话消息"）
     *
     * 注意：方法可见性为 package-private（不是 private），方便单测构造 H2 连接直接跑幂等验证。
     * 线上调用入口仍是 {@link #afterPropertiesSet()}。
     */
    void migrateAsyncTaskChatReply(Connection conn) {
        String table = "conversation_messages";
        if (!tableExists(conn, table)) {
            log.debug("[SchemaMigration] Table {} does not exist yet", table);
            return;
        }

        Set<String> existingColumns = getColumnNames(conn, table);
        Set<String> existingIndexes = getIndexNames(conn, table);

        // 1. source 列扩到 VARCHAR(20)（VARCHAR(10) 装不下 'ASYNC_TASK_RESULT'）
        if (existingColumns.contains("source")) {
            String currentType = getColumnType(conn, table, "source");
            if (currentType != null && !currentType.toUpperCase().startsWith("VARCHAR(20")) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE " + table +
                            " MODIFY COLUMN source VARCHAR(20) NOT NULL DEFAULT 'web' " +
                            "COMMENT '消息来源: web=网页端, api=API调用, ASYNC_TASK_RESULT=异步任务结果'");
                    log.info("[SchemaMigration] ✅ Altered column {}.source to VARCHAR(20)", table);
                } catch (Exception e) {
                    log.warn("[SchemaMigration] Failed to alter column {}.source: {}", table, e.getMessage());
                }
            }
        }

        // 2. async_task_id
        ensureColumn(conn, table, "async_task_id", existingColumns,
                "ALTER TABLE " + table + " ADD COLUMN async_task_id VARCHAR(64) DEFAULT NULL " +
                "COMMENT '关联 async_tasks.id（NULL=普通消息）'");

        // 3. summary_pending
        ensureColumn(conn, table, "summary_pending", existingColumns,
                "ALTER TABLE " + table + " ADD COLUMN summary_pending TINYINT(1) NOT NULL DEFAULT 1 " +
                "COMMENT 'LLM 续答总结是否尚未生成（1=pending，0=done）'");

        // 4. summary_text
        ensureColumn(conn, table, "summary_text", existingColumns,
                "ALTER TABLE " + table + " ADD COLUMN summary_text MEDIUMTEXT DEFAULT NULL " +
                "COMMENT 'LLM 续答生成的自然语言总结'");

        // 5. summary_generated_at
        ensureColumn(conn, table, "summary_generated_at", existingColumns,
                "ALTER TABLE " + table + " ADD COLUMN summary_generated_at DATETIME DEFAULT NULL " +
                "COMMENT 'LLM 续答完成时间'");

        // 6. async_task_id 索引
        ensureIndex(conn, table, "idx_conv_msg_async_task_id", existingIndexes,
                "CREATE INDEX idx_conv_msg_async_task_id ON " + table + "(async_task_id)");
    }

    /**
     * 读取列的 SQL 类型定义（例如 "VARCHAR(10)" / "TEXT" / "INT(11)"）。
     * 用于"列存在但类型不够"场景下的条件判断。
     */
    private String getColumnType(Connection conn, String table, String column) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to read column type for {}.{}: {}", table, column, e.getMessage());
        }
        return null;
    }

    private void ensureIndex(Connection conn, String table, String indexName,
                             Set<String> existing, String alterSql) {
        if (existing.contains(indexName.toLowerCase())) {
            log.debug("[SchemaMigration] Index {} on {} already exists, skip", indexName, table);
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(alterSql);
            log.info("[SchemaMigration] ✅ Added index {} on {}", indexName, table);
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to add index {} on {}: {}", indexName, table, e.getMessage());
        }
    }

    /**
     * llm-context-window-summarization change 配套 schema 迁移。
     *
     * 任务：新增 conversation_message_summaries 表，存"对话历史 LLM 摘要"
     * （按 conversation_id + covers_from_msg_id + covers_to_msg_id 唯一键缓存）。
     *
     * 不在 conversation_messages 表上加列，避免对现有热路径造成影响。
     */
    void migrateConversationMessageSummaries(Connection conn) {
        String table = "conversation_message_summaries";
        if (tableExists(conn, table)) {
            log.debug("[SchemaMigration] Table {} already exists, skip", table);
            return;
        }

        String createSql = "CREATE TABLE " + table + " (\n" +
                "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n" +
                "  conversation_id VARCHAR(64) NOT NULL,\n" +
                "  covers_from_msg_id BIGINT NOT NULL,\n" +
                "  covers_to_msg_id BIGINT NOT NULL,\n" +
                "  summary_text MEDIUMTEXT NOT NULL,\n" +
                "  model VARCHAR(64) NOT NULL,\n" +
                "  input_token_count INT,\n" +
                "  output_token_count INT,\n" +
                "  created_at DATETIME(3) NOT NULL,\n" +
                "  updated_at DATETIME(3) NOT NULL,\n" +
                "  UNIQUE KEY uk_conv_range (conversation_id, covers_from_msg_id, covers_to_msg_id),\n" +
                "  KEY idx_conv_created (conversation_id, created_at),\n" +
                "  CONSTRAINT fk_summaries_conv FOREIGN KEY (conversation_id)\n" +
                "    REFERENCES conversations(conversation_id) ON DELETE CASCADE\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n" +
                "  COMMENT '对话历史 LLM 摘要缓存（按 covers msg_id 范围）'";

        try (Statement st = conn.createStatement()) {
            st.executeUpdate(createSql);
            log.info("[SchemaMigration] ✅ Created table {}", table);
        } catch (Exception e) {
            log.warn("[SchemaMigration] Failed to create table {}: {}", table, e.getMessage());
        }
    }
}
