package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.entity.User;
import com.lobsterai.skillgateway.http.LlmHttpClient;
import com.lobsterai.skillgateway.mapper.SkillMapper;
import com.lobsterai.skillgateway.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步任务回灌对话流 + LLM 续答服务
 *
 * open spec: async-task-result-echo-to-chat
 *
 * 职责：
 * 1. 异步任务终态时，向 conversation_messages 写一条 ASYNC_TASK_RESULT 消息
 * 2. 触发 LLM 续答（用 user.llmApiBase/ModelName/ApiKey + env fallback）
 * 3. 续答成功 → UPDATE summary_text
 * 4. 续答失败 → 降级文本 UPDATE summary_text
 * 5. 写 LLM HTTP audit（用 auditTag=ASYNC_TASK_REPLY 区分于普通异步任务）
 *
 * 整个流程 fire-and-forget（@Async），不阻塞 AsyncTaskPollingScheduler 轮询线程。
 */
@Service
public class AsyncTaskChatReplyService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskChatReplyService.class);

    /** taskResult 截断上限（按字符数近似 20000 token；1 token ≈ 4 字符英文 / 1.5 字符中文）。 */
    private static final int TASK_RESULT_TRUNCATE_CHARS = 60000;  // ≈ 20000 token（保守估计）

    private final ChatMessageService chatMessageService;
    private final UserMapper userMapper;
    private final SkillMapper skillMapper;
    private final UserService userService;
    private final LlmHttpClient llmHttpClient;
    private final LlmHttpAuditService llmHttpAuditService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AsyncTaskChatReplyService(ChatMessageService chatMessageService,
                                     UserMapper userMapper,
                                     SkillMapper skillMapper,
                                     UserService userService,
                                     LlmHttpClient llmHttpClient,
                                     LlmHttpAuditService llmHttpAuditService,
                                     ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.userMapper = userMapper;
        this.skillMapper = skillMapper;
        this.userService = userService;
        this.llmHttpClient = llmHttpClient;
        this.llmHttpAuditService = llmHttpAuditService;
        this.objectMapper = objectMapper;
    }

    /**
     * fire-and-forget 入口：终态时由 AsyncTaskPollingScheduler 调一次。
     * 失败 catch 后只记 log，不影响轮询线程。
     */
    @Async("taskExecutor")
    public void onTaskTerminal(AsyncTask task) {
        if (task == null) {
            log.warn("[AsyncTaskChatReplyService] onTaskTerminal skipped: task is null");
            return;
        }
        // Bxdcbot 自主规划的子任务：不在对话流里单独回灌 ASYNC_TASK_RESULT
        // 由 BxdcbotRunCompletionService 统一写一条 BXDCBOT_RUN_RESULT 卡片即可
        // 通知中心仍能看到（async_tasks 行已存在）
        if (task.getParentToolId() != null && !task.getParentToolId().isEmpty()) {
            log.info("[AsyncTaskChatReplyService] Skip chat reply for Bxdcbot sub-task: taskId={} parentToolId={}",
                    task.getId(), task.getParentToolId());
            return;
        }
        try {
            // 1) 写一条对话消息（先 pending，等 LLM 续答再 update）
            String conversationId = task.getSessionId();
            String toolName = resolveToolName(task);
            String toolArgs = resolveToolArgs(task);
            String taskResult = buildTaskResultText(task, toolName, toolArgs);

            Long messageId = chatMessageService.insertAsyncTaskResult(conversationId, task, taskResult);
            if (messageId == null) {
                log.warn("[AsyncTaskChatReplyService] Skipping LLM reply: message insert failed (taskId={})", task.getId());
                return;
            }

            // 2) 触发 LLM 续答
            triggerLlmReply(task, toolName, toolArgs, messageId);
        } catch (Exception e) {
            // 兜底：fire-and-forget 任何异常都 catch，不外抛
            log.error("[AsyncTaskChatReplyService] onTaskTerminal failed for taskId={}: {}",
                    task.getId(), e.getMessage(), e);
        }
    }

    /**
     * 触发 LLM 续答：调 LLM + UPDATE summary_text（成功或降级）。
     */
    private void triggerLlmReply(AsyncTask task, String toolName, String toolArgs, Long messageId) {
        String userId = task.getUserId();
        User user = (userId != null) ? userMapper.selectById(userId) : null;
        Map<String, String> llmConfig = userService.mergeLlmConfigForAgent(user);

        String apiBase = llmConfig.get("llmApiBase");
        String apiKey = llmConfig.get("llmApiKey");
        String model = llmConfig.get("llmModelName");

        // 没配 apiKey 跳过 LLM 续答，直接降级
        if (apiKey == null || apiKey.isEmpty()) {
            log.info("[AsyncTaskChatReplyService] No LLM api key for userId={}, fallback to default text", userId);
            chatMessageService.updateLlmSummary(messageId, ChatMessageService.FALLBACK_SUMMARY_TEXT, false);
            return;
        }

        // 组装 prompt
        List<Map<String, String>> messages = assembleLlmPrompt(task, toolName, toolArgs);

        // 调 LLM
        long startedAtMs = System.currentTimeMillis();
        try {
            String llmOutput = llmHttpClient.chatCompletion(apiBase, apiKey, model, messages);
            long durationMs = System.currentTimeMillis() - startedAtMs;

            // audit
            recordLlmAudit(userId, model, apiBase, "ASYNC_TASK_REPLY", true, llmOutput != null ? llmOutput.length() : 0, durationMs, null);

            // 成功：UPDATE summary
            chatMessageService.updateLlmSummary(messageId, llmOutput, true);
            log.info("[AsyncTaskChatReplyService] LLM reply OK: taskId={}, messageId={}, len={}, durationMs={}",
                    task.getId(), messageId, llmOutput != null ? llmOutput.length() : 0, durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            // audit
            recordLlmAudit(userId, model, apiBase, "ASYNC_TASK_REPLY", false, 0, durationMs, e.getMessage());

            // 失败降级
            log.warn("[AsyncTaskChatReplyService] LLM reply failed for taskId={}: {} — fallback",
                    task.getId(), e.getMessage());
            chatMessageService.updateLlmSummary(messageId, ChatMessageService.FALLBACK_SUMMARY_TEXT, false);
        }
    }

    /**
     * 组装 LLM prompt（system + user）。
     */
    private List<Map<String, String>> assembleLlmPrompt(AsyncTask task, String toolName, String toolArgs) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> system = new HashMap<>();
        system.put("role", "system");
        system.put("content",
                "你是助手。用户的某次提问触发了异步任务，任务已经完成。\n" +
                "请基于任务结果，用**精炼简短的中文**告诉用户关键结论。\n" +
                "- 默认 80~180 字、3~6 句为佳；除非用户明确要展开，否则不要超过 250 字。\n" +
                "- 直奔结论在前，必要细节在后；不要寒暄、不要复述任务元信息、不要列 tool 入参。\n" +
                "- 不要重复调 tool，不要假装有更多结果。\n" +
                "- 回答格式：纯文本（Markdown 也可）。");
        messages.add(system);

        Map<String, String> user = new HashMap<>();
        user.put("role", "user");
        user.put("content",
                "## 异步任务结果\n\n" +
                "- externalTaskId: " + safe(task.getExternalTaskId()) + "\n" +
                "- status: " + safe(task.getStatus()) + "\n" +
                "- finished_at: " + (task.getCompletedAt() != null ? task.getCompletedAt().toString() : "") + "\n" +
                "- tool: " + safe(toolName) + "\n" +
                "- tool_args: " + safe(toolArgs) + "\n" +
                "\n" +
                "## 任务结果（已截断到 20000 token）\n\n" +
                truncateForLlm(task.getPollResult(), TASK_RESULT_TRUNCATE_CHARS));
        messages.add(user);

        return messages;
    }

    private void recordLlmAudit(String userId, String model, String apiBase, String auditTag,
                                boolean success, int responseLen, long durationMs, String errorMessage) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("correlationId", "async-task-reply-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId());
            payload.put("direction", "OUTBOUND");
            payload.put("userId", userId);
            payload.put("model", model);
            payload.put("apiBase", apiBase);
            payload.put("auditTag", auditTag);
            payload.put("success", success);
            payload.put("responseLen", responseLen);
            payload.put("durationMs", durationMs);
            if (errorMessage != null) {
                payload.put("errorMessage", errorMessage);
            }
            JsonNode payloadNode = objectMapper.valueToTree(payload);
            llmHttpAuditService.saveEvent(payloadNode);
        } catch (Exception e) {
            // audit 失败不阻塞主流程
            log.warn("[AsyncTaskChatReplyService] LLM audit save failed: {}", e.getMessage());
        }
    }

    private String resolveToolName(AsyncTask task) {
        if (task.getSkillId() == null) return "async_skill";
        try {
            Skill skill = skillMapper.selectById(task.getSkillId());
            if (skill != null && skill.getName() != null) {
                return skill.getName();
            }
        } catch (Exception e) {
            log.warn("[AsyncTaskChatReplyService] Failed to load skill {}: {}", task.getSkillId(), e.getMessage());
        }
        return "skill_" + task.getSkillId();
    }

    private String resolveToolArgs(AsyncTask task) {
        // SINGLE_CALL 模式：原始请求体
        String reqBody = task.getRequestBody();
        if (reqBody != null && !reqBody.isEmpty()) {
            return truncateForDisplay(reqBody, 2000);
        }
        return "(tool args not captured)";
    }

    /**
     * 构造任务结果文本（写到 conversation_messages.content 字段）。
     * 包括 status / externalTaskId / 完整 pollResult 摘要。
     */
    private String buildTaskResultText(AsyncTask task, String toolName, String toolArgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 异步任务完成\n\n");
        sb.append("- 任务 ID：").append(task.getId()).append("\n");
        sb.append("- 工具：").append(toolName).append("\n");
        sb.append("- 外部任务 ID：").append(safe(task.getExternalTaskId())).append("\n");
        sb.append("- 状态：").append(safe(task.getStatus())).append("\n");
        if (task.getCompletedAt() != null) {
            sb.append("- 完成时间：").append(task.getCompletedAt().toString()).append("\n");
        }
        if (task.getErrorMessage() != null && !task.getErrorMessage().isEmpty()) {
            sb.append("- 错误信息：").append(task.getErrorMessage()).append("\n");
        }
        sb.append("\n### 任务参数\n\n```json\n").append(toolArgs).append("\n```\n\n");
        sb.append("### 任务结果\n\n");
        String pollResult = task.getPollResult();
        if (pollResult == null || pollResult.isEmpty()) {
            sb.append("(无结果数据)");
        } else {
            sb.append("```\n").append(pollResult).append("\n```\n");
        }
        return sb.toString();
    }

    /**
     * LLM 续答时截断（保留头部 + "..." + 末尾，按字符数）。
     */
    private String truncateForLlm(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        int head = maxChars * 2 / 3;
        int tail = maxChars / 6;
        return s.substring(0, head) + "\n\n... [content truncated due to size, " +
                (s.length() - head - tail) + " chars omitted] ...\n\n" + s.substring(s.length() - tail);
    }

    private String truncateForDisplay(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
