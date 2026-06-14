package com.lobsterai.skillgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话消息表 - 存储每轮对话的完整消息内容。
 */
@TableName("conversation_messages")
public class ConversationMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("message_id")
    private String messageId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("skill_calls")
    private String skillCalls;

    @TableField("skill_outputs")
    private String skillOutputs;

    @TableField("source")
    private String source;

    /**
     * 关联 async_tasks.id（NULL=普通对话消息；非空=异步任务结果消息）。
     * 加于 async-task-result-echo-to-chat change。
     */
    @TableField("async_task_id")
    private String asyncTaskId;

    /**
     * LLM 续答总结是否尚未生成。1=pending（占位），0=done（已生成 summary_text）。
     * 加于 async-task-result-echo-to-chat change。
     */
    @TableField("summary_pending")
    private Integer summaryPending;

    /**
     * LLM 续答生成的自然语言总结（Markdown 文本）。
     * 加于 async-task-result-echo-to-chat change。
     */
    @TableField("summary_text")
    private String summaryText;

    /**
     * LLM 续答完成时间。
     * 加于 async-task-result-echo-to-chat change。
     */
    @TableField("summary_generated_at")
    private LocalDateTime summaryGeneratedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    // ---- Getters / Setters ----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSkillCalls() {
        return skillCalls;
    }

    public void setSkillCalls(String skillCalls) {
        this.skillCalls = skillCalls;
    }

    public String getSkillOutputs() {
        return skillOutputs;
    }

    public void setSkillOutputs(String skillOutputs) {
        this.skillOutputs = skillOutputs;
    }

    public String getSource() {
        return source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public String getAsyncTaskId() {
        return asyncTaskId;
    }
    public void setAsyncTaskId(String asyncTaskId) {
        this.asyncTaskId = asyncTaskId;
    }
    public Integer getSummaryPending() {
        return summaryPending;
    }
    public void setSummaryPending(Integer summaryPending) {
        this.summaryPending = summaryPending;
    }
    public String getSummaryText() {
        return summaryText;
    }
    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }
    public LocalDateTime getSummaryGeneratedAt() {
        return summaryGeneratedAt;
    }
    public void setSummaryGeneratedAt(LocalDateTime summaryGeneratedAt) {
        this.summaryGeneratedAt = summaryGeneratedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
