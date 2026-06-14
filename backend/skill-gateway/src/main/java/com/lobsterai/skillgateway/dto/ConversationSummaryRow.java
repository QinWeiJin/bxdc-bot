package com.lobsterai.skillgateway.dto;

import java.time.LocalDateTime;

/**
 * 对话历史 LLM 摘要缓存行（conversation_message_summaries 表）
 *
 * open spec: llm-context-window-summarization
 *
 * 缓存键：UNIQUE (conversation_id, covers_from_msg_id, covers_to_msg_id)
 * - covers_from_msg_id / covers_to_msg_id 是消息数组中的合成索引（long 类型）
 * - 不用真实 conversation_messages.id，因为 agent-core 送来的 messages 是
 *   LangChain state 里的，不一定有 DB 主键
 *
 * 字段语义：
 * - input_token_count / output_token_count 仅供监控用
 * - model 记的是生成这份 summary 用的 LLM（用户模型 or 系统默认）
 * - created_at / updated_at 自动维护
 */
public class ConversationSummaryRow {

    private Long id;
    private String conversationId;
    private Long coversFromMsgId;
    private Long coversToMsgId;
    private String summaryText;
    private String model;
    private Integer inputTokenCount;
    private Integer outputTokenCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ConversationSummaryRow() {}

    public ConversationSummaryRow(String conversationId, Long coversFromMsgId, Long coversToMsgId,
                                  String summaryText, String model,
                                  Integer inputTokenCount, Integer outputTokenCount) {
        this.conversationId = conversationId;
        this.coversFromMsgId = coversFromMsgId;
        this.coversToMsgId = coversToMsgId;
        this.summaryText = summaryText;
        this.model = model;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public Long getCoversFromMsgId() { return coversFromMsgId; }
    public void setCoversFromMsgId(Long coversFromMsgId) { this.coversFromMsgId = coversFromMsgId; }

    public Long getCoversToMsgId() { return coversToMsgId; }
    public void setCoversToMsgId(Long coversToMsgId) { this.coversToMsgId = coversToMsgId; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getInputTokenCount() { return inputTokenCount; }
    public void setInputTokenCount(Integer inputTokenCount) { this.inputTokenCount = inputTokenCount; }

    public Integer getOutputTokenCount() { return outputTokenCount; }
    public void setOutputTokenCount(Integer outputTokenCount) { this.outputTokenCount = outputTokenCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
