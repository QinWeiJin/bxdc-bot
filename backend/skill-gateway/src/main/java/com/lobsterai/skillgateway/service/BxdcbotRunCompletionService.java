package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.User;
import com.lobsterai.skillgateway.http.LlmHttpClient;
import com.lobsterai.skillgateway.mapper.UserMapper;
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
 * Bxdcbot run 终态回灌服务。
 *
 * open spec: bxdcbot-multi-turn-async（漏洞 1 修复）
 *
 * 职责：当 agent-core 的 Bxdcbot run 跑完（completed / failed / 60 轮触顶 / 全局超时）时，
 * agent-core 调 gateway 内部 API {@code POST /api/internal/bxdcbot-run/complete}，
 * 本服务负责：
 * <ol>
 *   <li>写一条 {@code source=BXDCBOT_RUN_RESULT} 的对话消息（幂等：按 parent_tool_id 去重）</li>
 *   <li>触发外层 LLM 续答（fire-and-forget，不阻塞 run 终结）</li>
 *   <li>续答成功 → UPDATE summary_text</li>
 *   <li>续答失败 → 降级文本 UPDATE summary_text</li>
 * </ol>
 *
 * 关键设计：本服务**不修改** {@code AsyncTaskChatReplyService} 已有逻辑（避免影响普通 async 续答流程），
 * 而是独立新增一条 Bxdcbot run 终态的续答路径，{@code source=BXDCBOT_RUN_RESULT} 是新的枚举值。
 */
@Service
public class BxdcbotRunCompletionService {

    private static final Logger log = LoggerFactory.getLogger(BxdcbotRunCompletionService.class);

    /** Bxdcbot run 终态总结的截断上限（finalText 字段）。 */
    private static final int FINAL_TEXT_TRUNCATE_CHARS = 60000;

    private final ChatMessageService chatMessageService;
    private final UserMapper userMapper;
    private final UserService userService;
    private final LlmHttpClient llmHttpClient;
    private final LlmHttpAuditService llmHttpAuditService;
    private final ObjectMapper objectMapper;

    @Autowired
    public BxdcbotRunCompletionService(ChatMessageService chatMessageService,
                                       UserMapper userMapper,
                                       UserService userService,
                                       LlmHttpClient llmHttpClient,
                                       LlmHttpAuditService llmHttpAuditService,
                                       ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.userMapper = userMapper;
        this.userService = userService;
        this.llmHttpClient = llmHttpClient;
        this.llmHttpAuditService = llmHttpAuditService;
        this.objectMapper = objectMapper;
    }

    /**
     * fire-and-forget 入口：agent-core 调 {@code /api/internal/bxdcbot-run/complete} 时由 Controller 调一次。
     * 失败 catch 后只记 log，不外抛。
     */
    @Async("taskExecutor")
    public void onRunComplete(BxdcbotRunCompleteRequest req) {
        if (req == null) {
            log.warn("[BxdcbotRunCompletionService] onRunComplete skipped: req is null");
            return;
        }
        try {
            // 1) 写一条 BXDCBOT_RUN_RESULT 对话消息（幂等）
            String content = buildContent(req);
            Long messageId = chatMessageService.insertBxdcbotRunResult(
                    req.conversationId, req.runId, req.parentSkillId, content);
            if (messageId == null) {
                log.warn("[BxdcbotRunCompletionService] Skipping LLM reply: message insert failed (runId={})", req.runId);
                return;
            }

            // 2) 触发 LLM 续答
            triggerLlmReply(req, messageId);
        } catch (Exception e) {
            log.error("[BxdcbotRunCompletionService] onRunComplete failed for runId={}: {}",
                    req.runId, e.getMessage(), e);
        }
    }

    /**
     * 组装 BXDCBOT_RUN_RESULT 消息的 content（status / roundsUsed / subTaskSummary / finalText）。
     * 用户在前端看到的是这个 content（与 ASYNC_TASK_RESULT 区别：前缀带 "[Bxdcbot 整体结果]"）。
     */
    private String buildContent(BxdcbotRunCompleteRequest req) {
        StringBuilder sb = new StringBuilder();

        // 结构化 JSON（前端 BxdcbotRunResultMessage 组件解析）
        sb.append("{");

        sb.append("\"status\":\"").append(req.status == null ? "unknown" : req.status).append("\"");

        // 主 skill 名称（用于卡片头部展示主 skill 名）
        if (req.parentSkillName != null && !req.parentSkillName.isEmpty()) {
            sb.append(", \"parentSkillName\":\"").append(escapeJson(req.parentSkillName)).append("\"");
        }

        if (req.failureReason != null && !req.failureReason.isEmpty()) {
            sb.append(", \"failureReason\":\"").append(escapeJson(req.failureReason)).append("\"");
        }
        if (req.roundsUsed != null) {
            sb.append(", \"roundsUsed\":").append(req.roundsUsed);
        }
        if (req.llmCallsUsed != null) {
            sb.append(", \"totalLlmCalls\":").append(req.llmCallsUsed);
        }

        // 子任务计数
        if (req.subTaskSummary != null && !req.subTaskSummary.isEmpty()) {
            try {
                JsonNode summaryNode = objectMapper.readTree(req.subTaskSummary);
                if (summaryNode.has("total")) sb.append(", \"totalCount\":").append(summaryNode.get("total").asInt());
                if (summaryNode.has("succeeded")) sb.append(", \"completedCount\":").append(summaryNode.get("succeeded").asInt());
                if (summaryNode.has("failed")) sb.append(", \"failedCount\":").append(summaryNode.get("failed").asInt());
            } catch (Exception ignore) {
                sb.append(", \"subTaskSummary\":\"").append(escapeJson(req.subTaskSummary)).append("\"");
            }
        }

        // 子技能执行详情
        if (req.subTaskResults != null && !req.subTaskResults.isEmpty()) {
            StringBuilder steps = new StringBuilder();
            steps.append("[");
            boolean first = true;
            for (SubTaskResult sr : req.subTaskResults) {
                if (!first) steps.append(", ");
                first = false;
                steps.append("{");
                steps.append("\"skillName\":\"").append(escapeJson(sr.skillName)).append("\"");
                steps.append(", \"status\":\"").append(sr.status == null ? "unknown" : sr.status).append("\"");
                if (sr.result != null) {
                    steps.append(", \"result\":\"").append(escapeJson(sr.result)).append("\"");
                }
                steps.append("}");
            }
            steps.append("]");
            sb.append(", \"subTaskSteps\":").append(steps.toString());
        }

        // 最终文本
        if (req.finalText != null && !req.finalText.isEmpty()) {
            sb.append(", \"finalText\":\"").append(escapeJson(truncate(req.finalText, FINAL_TEXT_TRUNCATE_CHARS))).append("\"");
        }

        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 触发 LLM 续答：用 user.llm_config（与 AsyncTaskChatReplyService 行为一致）调 LLM。
     * 续答成功 → UPDATE summary_text；失败 → 降级文本 UPDATE summary_text。
     */
    private void triggerLlmReply(BxdcbotRunCompleteRequest req, Long messageId) {
        String userId = req.userId;
        User user = (userId != null) ? userMapper.selectById(userId) : null;
        Map<String, String> llmConfig = userService.mergeLlmConfigForAgent(user);

        String apiBase = llmConfig.get("llmApiBase");
        String apiKey = llmConfig.get("llmApiKey");
        String model = llmConfig.get("llmModelName");

        if (apiKey == null || apiKey.isEmpty()) {
            log.info("[BxdcbotRunCompletionService] No LLM api key for userId={}, fallback to default text", userId);
            chatMessageService.updateLlmSummary(messageId, ChatMessageService.FALLBACK_SUMMARY_TEXT, false);
            return;
        }

        List<Map<String, String>> messages = assembleLlmPrompt(req);

        long startedAtMs = System.currentTimeMillis();
        try {
            String llmOutput = llmHttpClient.chatCompletion(apiBase, apiKey, model, messages);
            long durationMs = System.currentTimeMillis() - startedAtMs;

            recordLlmAudit(userId, model, apiBase, true, llmOutput != null ? llmOutput.length() : 0, durationMs, null);

            chatMessageService.updateLlmSummary(messageId, llmOutput, true);
            log.info("[BxdcbotRunCompletionService] LLM reply OK: runId={}, messageId={}, len={}, durationMs={}",
                    req.runId, messageId, llmOutput != null ? llmOutput.length() : 0, durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            recordLlmAudit(userId, model, apiBase, false, 0, durationMs, e.getMessage());

            log.warn("[BxdcbotRunCompletionService] LLM reply failed for runId={}: {} — fallback",
                    req.runId, e.getMessage());
            chatMessageService.updateLlmSummary(messageId, ChatMessageService.FALLBACK_SUMMARY_TEXT, false);
        }
    }

    /**
     * 组装 Bxdcbot run 续答 LLM prompt。
     * 设计依据 spec 决策 10：跨层关联用 parentToolId 反查 chat_messages
     * 找外层 "调 Bxdcbot 的 user 消息 + assistant tool_call" 作为续答 context。
     * MVP 阶段：parentToolId 反查需要 conversationId + 反查 chat_messages，agent-core 调 complete 时
     * 已经把外层 user 消息和 tool call 摘要拼到 finalText 字段里（agent-core 侧负责跨层关联），
     * 所以本服务只需把 finalText + subTaskSummary 喂给 LLM，prompt 提示 LLM 用自然语言回应用户。
     */
    private List<Map<String, String>> assembleLlmPrompt(BxdcbotRunCompleteRequest req) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> system = new HashMap<>();
        system.put("role", "system");
        system.put("content",
                "你是助手。用户调了一个 Bxdcbot 自主规划 skill（run " + safe(req.runId) + "），" +
                "已 " + (req.status == null ? "完成" : req.status.toLowerCase()) + "。\n" +
                "请基于 Bxdcbot 最终输出 + 子 async 任务汇总，用**精炼简短的中文**回应用户。\n" +
                "- 默认 100~250 字、4~8 句为佳；除非用户明确要展开，否则不要超过 350 字。\n" +
                "- 直奔结论在前，必要细节在后；不要寒暄、不要复述任务元信息。\n" +
                "- 如果 run 失败（status=failed），明确告知失败原因 + 建议用户怎么办。\n" +
                "- 不要重复调 tool，不要假装有更多结果。\n" +
                "- 回答格式：纯文本（Markdown 也可）。");
        messages.add(system);

        Map<String, String> user = new HashMap<>();
        user.put("role", "user");
        user.put("content",
                "## Bxdcbot run " + safe(req.runId) + "\n\n" +
                "- status: " + safe(req.status) + "\n" +
                (req.failureReason != null ? "- failureReason: " + safe(req.failureReason) + "\n" : "") +
                "- roundsUsed: " + (req.roundsUsed == null ? "?" : req.roundsUsed) + "\n" +
                "- llmCallsUsed: " + (req.llmCallsUsed == null ? "?" : req.llmCallsUsed) + "\n" +
                (req.subTaskSummary != null && !req.subTaskSummary.isEmpty() ? "- subTaskSummary: " + safe(req.subTaskSummary) + "\n" : "") +
                (req.parentSkillId != null ? "- parentSkillId: " + req.parentSkillId + "\n" : "") +
                "\n" +
                "## Bxdcbot 最终输出（已截断到 20000 token）\n\n" +
                (req.finalText != null ? truncate(req.finalText, FINAL_TEXT_TRUNCATE_CHARS) : "(无)"));
        messages.add(user);

        return messages;
    }

    private void recordLlmAudit(String userId, String model, String apiBase,
                                boolean success, int responseLen, long durationMs, String errorMessage) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("correlationId", "bxdcbot-run-reply-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId());
            payload.put("direction", "OUTBOUND");
            payload.put("userId", userId);
            payload.put("model", model);
            payload.put("apiBase", apiBase);
            payload.put("auditTag", "BXDCBOT_RUN_REPLY");
            payload.put("success", success);
            payload.put("responseLen", responseLen);
            payload.put("durationMs", durationMs);
            if (errorMessage != null) {
                payload.put("errorMessage", errorMessage);
            }
            JsonNode payloadNode = objectMapper.valueToTree(payload);
            llmHttpAuditService.saveEvent(payloadNode);
        } catch (Exception e) {
            log.warn("[BxdcbotRunCompletionService] Failed to record LLM audit: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() > maxChars ? s.substring(0, maxChars) + "...[truncated]" : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Bxdcbot run 终态 callback 请求体（内部 API，不走 user 鉴权）。
     */
    public static class BxdcbotRunCompleteRequest {
        public String runId;
        public String conversationId;
        public String userId;
        public String parentToolId;  // 通常 = runId
        public Long parentSkillId;
        /** 父 skill 名称（用于卡片头部展示，前端可不解析，依赖前端查 name） */
        public String parentSkillName;
        public String status;  // "completed" | "failed" | "timeout"
        public String finalText;  // completed 时填
        public String failureReason;  // failed / timeout 时填
        public Integer roundsUsed;
        public Integer llmCallsUsed;
        public Integer totalTokensUsed;
        /** 子 async 任务汇总 JSON 字符串，例 "{\"total\":3,\"succeeded\":2,\"failed\":1,\"pending\":0}" */
        public String subTaskSummary;
        /** 子技能执行详情列表 */
        public java.util.List<SubTaskResult> subTaskResults;
        public String finishedAt;  // ISO8601
    }

    /** 子技能执行结果 */
    public static class SubTaskResult {
        public String skillName;
        public String status;  // "completed" | "async_pending" | "failed"
        public String result;
        public Integer asyncTaskId;
        public String completedAt;
    }
}
