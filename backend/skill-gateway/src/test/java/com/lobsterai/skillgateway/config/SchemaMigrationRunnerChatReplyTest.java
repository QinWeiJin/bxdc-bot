package com.lobsterai.skillgateway.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SchemaMigrationRunner.migrateAsyncTaskChatReply 幂等性测试（open spec: async-task-result-echo-to-chat）。
 *
 * 策略：用 H2 in-memory DB + MODE=MySQL（让 information_schema 行为贴近 MySQL）。
 * 手动构造 conversation_messages 表（仅含基础列，不含新增列），然后：
 * 1. 第一次跑迁移 → 验证新列 + 索引都被加上
 * 2. 第二次跑迁移（幂等） → 不报错，所有列/索引仍在
 * 3. 第三次跑迁移（幂等） → 仍然 OK
 *
 * 每个测试方法用独立 DB 名（UUID）确保隔离。
 *
 * 注：H2 默认把 unquoted identifier 转大写（与 MySQL 不同）。这里模仿 SchemaMigrationRunner 自身
 * 用的 `information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()` 模式做查询，
 * 不硬编码 TABLE_NAME 大小写。
 */
class SchemaMigrationRunnerChatReplyTest {

    private Connection conn;
    private SchemaMigrationRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        // 用全小写 DB 名 + DATABASE_TO_LOWER + CASE_INSENSITIVE_IDENTIFIERS，让 H2 行为贴近 MySQL：
        // - DATABASE() 返回小写 DB 名
        // - unquoted identifier 保持小写
        // - 大小写比较不敏感
        String dbName = ("migration_chat_reply_test_" + java.util.UUID.randomUUID().toString().replace("-", "")).toLowerCase();
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "password"
        );

        try (Statement st = conn.createStatement()) {
            // 建一个与 DB 同名的 schema 并 SET SCHEMA，让 TABLE_SCHEMA = DATABASE() 匹配上
            st.execute("CREATE SCHEMA IF NOT EXISTS \"" + dbName + "\"");
            st.execute("SET SCHEMA \"" + dbName + "\"");

            // 手动建 conversation_messages 表（最小骨架，模拟"线上已有但缺少新列"的场景）
            st.executeUpdate(
                    "CREATE TABLE conversation_messages (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                            "message_id VARCHAR(64) NOT NULL, " +
                            "conversation_id VARCHAR(64) NOT NULL, " +
                            "role VARCHAR(20) NOT NULL DEFAULT 'user', " +
                            "content MEDIUMTEXT, " +
                            "skill_calls MEDIUMTEXT, " +
                            "skill_outputs MEDIUMTEXT, " +
                            "source VARCHAR(10) NOT NULL DEFAULT 'web', " +
                            "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
        }

        runner = new SchemaMigrationRunner(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    /** 读取某表的所有列名（小写），与 SchemaMigrationRunner.getColumnNames 同样的查询模式。 */
    private Set<String> columnsOf(String table) throws Exception {
        Set<String> cols = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1).toLowerCase());
                }
            }
        }
        return cols;
    }

    /** 读取某表的索引名集合。
     *  SchemaMigrationRunner 用的是 MySQL 的 information_schema.STATISTICS；H2 MODE=MySQL 没这个表，
     *  改用 H2 原生的 information_schema.INDEXES 做断言。runner 自身 catch 所有异常，所以缺 STATISTICS
     *  不会让迁移失败（仅 ensureIndex 会走"create → 已存在 catch"路径）。
     */
    private Set<String> indexesOf(String table) throws Exception {
        Set<String> idx = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT INDEX_NAME FROM information_schema.INDEXES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null) idx.add(name.toLowerCase());
                }
            }
        }
        return idx;
    }

    /** 读取某列的 SQL 类型，与 SchemaMigrationRunner.getColumnType 同样的查询模式。 */
    private String columnTypeOf(String table, String column) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1).toUpperCase();
                }
            }
        }
        return null;
    }

    @Test
    void firstRun_addsAllNewColumnsAndIndex() throws Exception {
        // Act
        runner.migrateAsyncTaskChatReply(conn);

        // Assert: 4 个新列都已加
        Set<String> cols = columnsOf("conversation_messages");
        assertTrue(cols.contains("async_task_id"), "async_task_id 列应被添加，实际: " + cols);
        assertTrue(cols.contains("summary_pending"), "summary_pending 列应被添加");
        assertTrue(cols.contains("summary_text"), "summary_text 列应被添加");
        assertTrue(cols.contains("summary_generated_at"), "summary_generated_at 列应被添加");

        // source 应该被扩到 VARCHAR(20)
        String sourceType = columnTypeOf("conversation_messages", "source");
        assertNotNull(sourceType);
        assertTrue(sourceType.contains("VARCHAR"), "source 列类型应为 VARCHAR，实际: " + sourceType);

        // idx_conv_msg_async_task_id 索引应存在
        Set<String> idx = indexesOf("conversation_messages");
        assertTrue(idx.contains("idx_conv_msg_async_task_id"),
                "索引应被创建，实际: " + idx);
    }

    @Test
    void secondRun_idempotent_noErrorNoDuplicateColumns() throws Exception {
        // Arrange: 第一次跑
        runner.migrateAsyncTaskChatReply(conn);
        Set<String> colsAfterFirst = columnsOf("conversation_messages");
        Set<String> idxAfterFirst = indexesOf("conversation_messages");
        int firstColCount = colsAfterFirst.size();
        int firstIdxCount = idxAfterFirst.size();

        // Act: 第二次跑（幂等）
        runner.migrateAsyncTaskChatReply(conn);

        // Assert: 列数/索引数都不变，新列仍在
        Set<String> colsAfterSecond = columnsOf("conversation_messages");
        Set<String> idxAfterSecond = indexesOf("conversation_messages");
        assertTrue(colsAfterSecond.size() == firstColCount,
                "第二次跑不应增/删列，第一次=" + firstColCount + "，第二次=" + colsAfterSecond.size());
        assertTrue(idxAfterSecond.size() == firstIdxCount,
                "第二次跑不应增/删索引，第一次=" + firstIdxCount + "，第二次=" + idxAfterSecond.size());
        assertTrue(colsAfterSecond.contains("async_task_id"));
        assertTrue(colsAfterSecond.contains("summary_pending"));
        assertTrue(colsAfterSecond.contains("summary_text"));
        assertTrue(colsAfterSecond.contains("summary_generated_at"));
        assertTrue(idxAfterSecond.contains("idx_conv_msg_async_task_id"));
    }

    @Test
    void thirdRun_stillIdempotent() throws Exception {
        // 跑 3 次幂等
        runner.migrateAsyncTaskChatReply(conn);
        runner.migrateAsyncTaskChatReply(conn);
        runner.migrateAsyncTaskChatReply(conn);

        Set<String> cols = columnsOf("conversation_messages");
        assertTrue(cols.contains("async_task_id"));
        assertTrue(cols.contains("summary_pending"));
        assertTrue(cols.contains("summary_text"));
        assertTrue(cols.contains("summary_generated_at"));
        assertTrue(indexesOf("conversation_messages").contains("idx_conv_msg_async_task_id"));
    }

    @Test
    void missingTable_doesNotThrow() throws Exception {
        // 删除 conversation_messages 表
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE conversation_messages");
        }

        // 迁移应该 silent skip（不抛异常）
        runner.migrateAsyncTaskChatReply(conn);

        // 表仍不存在
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'CONVERSATION_MESSAGES'")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) == 0, "表不应被自动重建");
        }
    }
}