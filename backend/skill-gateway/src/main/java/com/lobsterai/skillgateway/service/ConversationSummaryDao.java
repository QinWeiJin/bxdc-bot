package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.dto.ConversationSummaryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 对话历史 LLM 摘要缓存 DAO（JdbcTemplate 直连，未走 MyBatis）
 *
 * open spec: llm-context-window-summarization
 *
 * 设计取舍：用 JdbcTemplate 而非 MyBatis-Plus Mapper，理由：
 * - 单表、CRUD 简单，不值得为它建 Entity + Mapper 文件
 * - JdbcTemplate 跑得最快，零 ORM 开销
 * - 单测容易 mock（直接 stub JdbcTemplate.query）
 */
@Repository
public class ConversationSummaryDao {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryDao.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ConversationSummaryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ConversationSummaryRow> ROW_MAPPER = (rs, rowNum) -> {
        ConversationSummaryRow row = new ConversationSummaryRow();
        row.setId(rs.getLong("id"));
        row.setConversationId(rs.getString("conversation_id"));
        row.setCoversFromMsgId(rs.getLong("covers_from_msg_id"));
        row.setCoversToMsgId(rs.getLong("covers_to_msg_id"));
        row.setSummaryText(rs.getString("summary_text"));
        row.setModel(rs.getString("model"));
        int inputTokens = rs.getInt("input_token_count");
        row.setInputTokenCount(rs.wasNull() ? null : inputTokens);
        int outputTokens = rs.getInt("output_token_count");
        row.setOutputTokenCount(rs.wasNull() ? null : outputTokens);
        Timestamp createdTs = rs.getTimestamp("created_at");
        if (createdTs != null) row.setCreatedAt(createdTs.toLocalDateTime());
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        if (updatedTs != null) row.setUpdatedAt(updatedTs.toLocalDateTime());
        return row;
    };

    /**
     * 按 (conversation_id, from, to) 查 summary。
     */
    public Optional<ConversationSummaryRow> findSummary(String conversationId, Long fromMsgId, Long toMsgId) {
        String sql = "SELECT * FROM conversation_message_summaries " +
                "WHERE conversation_id = ? AND covers_from_msg_id = ? AND covers_to_msg_id = ?";
        try {
            List<ConversationSummaryRow> rows = jdbcTemplate.query(sql, ROW_MAPPER,
                    conversationId, fromMsgId, toMsgId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            log.warn("[ConversationSummaryDao] findSummary failed (convId={}, from={}, to={}): {}",
                    conversationId, fromMsgId, toMsgId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 按 (conversation_id, to) 查"覆盖范围结束在 to 之前"的最新 summary（用于增量扩展）。
     */
    public Optional<ConversationSummaryRow> findLatestBefore(String conversationId, Long toMsgId) {
        String sql = "SELECT * FROM conversation_message_summaries " +
                "WHERE conversation_id = ? AND covers_to_msg_id < ? " +
                "ORDER BY covers_to_msg_id DESC LIMIT 1";
        try {
            List<ConversationSummaryRow> rows = jdbcTemplate.query(sql, ROW_MAPPER,
                    conversationId, toMsgId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            log.warn("[ConversationSummaryDao] findLatestBefore failed (convId={}, to={}): {}",
                    conversationId, toMsgId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Upsert summary：已存在则更新 summary_text + updated_at + model；不存在则插入。
     */
    public ConversationSummaryRow saveSummary(ConversationSummaryRow row) {
        String sql = "INSERT INTO conversation_message_summaries " +
                "(conversation_id, covers_from_msg_id, covers_to_msg_id, summary_text, " +
                " model, input_token_count, output_token_count, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3), NOW(3)) " +
                "ON DUPLICATE KEY UPDATE " +
                "  summary_text = VALUES(summary_text), " +
                "  model = VALUES(model), " +
                "  input_token_count = VALUES(input_token_count), " +
                "  output_token_count = VALUES(output_token_count), " +
                "  updated_at = NOW(3)";
        try {
            jdbcTemplate.update(sql,
                    row.getConversationId(),
                    row.getCoversFromMsgId(),
                    row.getCoversToMsgId(),
                    row.getSummaryText(),
                    row.getModel(),
                    row.getInputTokenCount(),
                    row.getOutputTokenCount());
            return findSummary(row.getConversationId(),
                    row.getCoversFromMsgId(), row.getCoversToMsgId()).orElse(row);
        } catch (Exception e) {
            log.warn("[ConversationSummaryDao] saveSummary failed (convId={}, from={}, to={}): {}",
                    row.getConversationId(), row.getCoversFromMsgId(), row.getCoversToMsgId(),
                    e.getMessage());
            return row;
        }
    }
}
