package com.lobsterai.skillgateway.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.ConversationMessage;
import com.lobsterai.skillgateway.event.ConversationEventBus;
import com.lobsterai.skillgateway.mapper.ConversationMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对话消息服务 - 异步任务结果写入专用
 *
 * open spec: async-task-result-echo-to-chat
 *
 * 负责把 async_tasks 终态结果"回灌"到 conversation_messages 表。
 *
 * 设计要点（避免与现有 ConversationMessageService 冲突）：
 * - 不新增 entity，复用 ConversationMessage（4 个新字段已在 entity 扩展）
 * - 不新增 mapper，复用 ConversationMessageMapper
 * - 本 service 专门封装"异步任务结果消息"的写入/更新语义
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    /**
     * 消息来源枚举值：异步任务结果。
     * 与 spec 决议 2 一致（VARCHAR 风格，不强制 ENUM）。
     */
    public static final String SOURCE_ASYNC_TASK_RESULT = "ASYNC_TASK_RESULT";

    /**
     * 降级文本（spec 决策 6：LLM 续答失败时 summary_text 用此）。
     */
    public static final String FALLBACK_SUMMARY_TEXT =
            "任务已完成（系统未生成总结，可点击下方\"查看完整任务\"了解结果）";

    private final ConversationMessageMapper messageMapper;
    private final ConversationEventBus eventBus;

    @Autowired
    public ChatMessageService(ConversationMessageMapper messageMapper,
                              ConversationEventBus eventBus) {
        this.messageMapper = messageMapper;
        this.eventBus = eventBus;
    }

    /**
     * 写入一条异步任务结果消息（summary_pending=true，等 LLM 续答回来再 UPDATE）。
     *
     * @param conversationId 对话 ID（来自 async_tasks.session_id 或外部）
     * @param task           异步任务实体
     * @param taskResult     任务结果摘要（截断后的，可能很长）
     * @return 写入的消息 id
     */
    public Long insertAsyncTaskResult(String conversationId, AsyncTask task, String taskResult) {
        if (conversationId == null || conversationId.isEmpty()) {
            log.warn("[ChatMessageService] insertAsyncTaskResult skipped: conversationId is empty");
            return null;
        }
        if (task == null) {
            log.warn("[ChatMessageService] insertAsyncTaskResult skipped: task is null");
            return null;
        }

        ConversationMessage msg = new ConversationMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setConversationId(conversationId);
        msg.setRole("assistant");
        msg.setSource(SOURCE_ASYNC_TASK_RESULT);
        msg.setContent(taskResult != null ? taskResult : "");
        msg.setAsyncTaskId(String.valueOf(task.getId()));
        msg.setSummaryPending(1);
        msg.setSummaryText(null);
        msg.setSummaryGeneratedAt(null);
        msg.setCreatedAt(LocalDateTime.now());

        try {
            messageMapper.insert(msg);
            log.info("[ChatMessageService] Inserted async task result message: id={}, conversationId={}, asyncTaskId={}",
                    msg.getId(), conversationId, task.getId());
            // SSE 推送：让前端对话流实时出现新消息
            eventBus.publish(conversationId, "message_inserted", toMessageDto(msg));
            return msg.getId();
        } catch (Exception e) {
            log.error("[ChatMessageService] Failed to insert async task result message: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * UPDATE 续答总结（LLM 续答成功 / 失败都调）。
     *
     * @param messageId    主键
     * @param summaryText  总结文本（失败时用 {@link #FALLBACK_SUMMARY_TEXT}）
     * @param success      true=LLM 成功 / false=降级
     */
    public void updateLlmSummary(Long messageId, String summaryText, boolean success) {
        if (messageId == null) {
            log.warn("[ChatMessageService] updateLlmSummary skipped: messageId is null");
            return;
        }

        ConversationMessage update = new ConversationMessage();
        update.setSummaryText(summaryText);
        update.setSummaryPending(0);
        update.setSummaryGeneratedAt(LocalDateTime.now());

        LambdaUpdateWrapper<ConversationMessage> wrapper = new LambdaUpdateWrapper<ConversationMessage>()
                .eq(ConversationMessage::getId, messageId);

        try {
            int rows = messageMapper.update(update, wrapper);
            if (rows == 0) {
                log.warn("[ChatMessageService] updateLlmSummary: no row updated for messageId={} (may have been deleted)", messageId);
            } else {
                log.info("[ChatMessageService] Updated LLM summary for messageId={}, success={}, len={}",
                        messageId, success, summaryText != null ? summaryText.length() : 0);
                // SSE 推送：让前端"骨架屏→真实总结"实时切换
                ConversationMessage fresh = messageMapper.selectById(messageId);
                if (fresh != null) {
                    eventBus.publish(fresh.getConversationId(), "message_updated", toMessageDto(fresh));
                }
            }
        } catch (Exception e) {
            log.error("[ChatMessageService] Failed to update LLM summary for messageId={}: {}", messageId, e.getMessage(), e);
        }
    }

    /**
     * 把消息实体序列化为前端 message DTO。
     * 形状与 {@code ConversationService.getMessages} 返回的 message 保持一致，
     * 避免前端 SSE 收到的事件和拉取历史的 messages 形状不一致。
     */
    private Map<String, Object> toMessageDto(ConversationMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message_id", msg.getMessageId());
        m.put("role", msg.getRole());
        m.put("content", msg.getContent());
        m.put("skill_calls", msg.getSkillCalls());
        m.put("skill_outputs", msg.getSkillOutputs());
        m.put("source", msg.getSource());
        m.put("async_task_id", msg.getAsyncTaskId());
        m.put("summary_pending", msg.getSummaryPending());
        m.put("summary_text", msg.getSummaryText());
        m.put("summary_generated_at", msg.getSummaryGeneratedAt() != null ? msg.getSummaryGeneratedAt().toString() : null);
        m.put("created_at", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        return m;
    }
}
