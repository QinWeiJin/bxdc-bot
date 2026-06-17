package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.service.AsyncTaskPollingService;
import com.lobsterai.skillgateway.service.BxdcbotRunCompletionService;
import com.lobsterai.skillgateway.service.BxdcbotRunCompletionService.BxdcbotRunCompleteRequest;
import com.lobsterai.skillgateway.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bxdcbot run 终态 callback 内部 API。
 *
 * open spec: bxdcbot-multi-turn-async（漏洞 1 修复）
 *
 * agent-core 在 Bxdcbot run 跑完（completed / failed / 60 轮触顶 / 全局超时）时调一次本端点。
 * gateway 收到后：
 * <ol>
 *   <li>写一条 {@code source=BXDCBOT_RUN_RESULT} 的对话消息（幂等：按 parent_tool_id 去重）</li>
 *   <li>触发外层 LLM 续答（fire-and-forget，不阻塞 run 终结）</li>
 * </ol>
 *
 * 鉴权：{@code X-Internal-Token} == env {@code INTERNAL_API_TOKEN}（与 ConversationController.compact
 * 端点鉴权机制一致）。
 *
 * 关键设计：本 Controller **不影响** 普通 async 任务的回灌（{@code AsyncTaskChatReplyService} 路径），
 * 是独立的"run 整体结果"回灌通道。前端通过 {@code source=BXDCBOT_RUN_RESULT} 区分视觉渲染。
 */
@RestController
@RequestMapping("/api/internal/bxdcbot-run")
@CrossOrigin(origins = "*")
public class BxdcbotRunCompletionController {

    private static final Logger log = LoggerFactory.getLogger(BxdcbotRunCompletionController.class);

    private final BxdcbotRunCompletionService bxdcbotRunCompletionService;
    private final SkillService skillService;
    private final AsyncTaskPollingService asyncTaskPollingService;

    public BxdcbotRunCompletionController(BxdcbotRunCompletionService bxdcbotRunCompletionService,
                                           SkillService skillService,
                                           AsyncTaskPollingService asyncTaskPollingService) {
        this.bxdcbotRunCompletionService = bxdcbotRunCompletionService;
        this.skillService = skillService;
        this.asyncTaskPollingService = asyncTaskPollingService;
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @RequestBody Map<String, Object> body) {
        // 鉴权：X-Internal-Token == env INTERNAL_API_TOKEN
        String expected = System.getProperty("INTERNAL_API_TOKEN");
        if (expected == null || expected.isEmpty()) {
            expected = System.getenv("INTERNAL_API_TOKEN");
        }
        if (expected == null || expected.isEmpty()) {
            log.debug("[BxdcbotRunCompletion] INTERNAL_API_TOKEN not set, allowing (dev mode)");
        } else if (internalToken == null || !expected.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("error", "invalid_internal_token"));
        }

        BxdcbotRunCompleteRequest req = new BxdcbotRunCompleteRequest();
        req.runId = (String) body.get("runId");
        req.conversationId = (String) body.get("conversationId");
        req.userId = (String) body.get("userId");
        req.parentToolId = (String) body.get("parentToolId");
        req.parentSkillId = body.get("parentSkillId") instanceof Number
                ? ((Number) body.get("parentSkillId")).longValue() : null;
        req.status = (String) body.get("status");
        req.finalText = (String) body.get("finalText");
        req.failureReason = (String) body.get("failureReason");
        req.roundsUsed = body.get("roundsUsed") instanceof Number
                ? ((Number) body.get("roundsUsed")).intValue() : null;
        req.llmCallsUsed = body.get("llmCallsUsed") instanceof Number
                ? ((Number) body.get("llmCallsUsed")).intValue() : null;
        req.totalTokensUsed = body.get("totalTokensUsed") instanceof Number
                ? ((Number) body.get("totalTokensUsed")).intValue() : null;
        req.subTaskSummary = (String) body.get("subTaskSummary");
        req.parentSkillName = (String) body.get("parentSkillName");
        // 若 agent-core 没传 parentSkillName，按 parentSkillId 查 skill 名称
        if ((req.parentSkillName == null || req.parentSkillName.isEmpty()) && req.parentSkillId != null && req.userId != null) {
            req.parentSkillName = skillService.getSkillByIdForUser(req.parentSkillId, req.userId)
                    .map(Skill::getName)
                    .orElse(null);
        }
        req.finishedAt = (String) body.get("finishedAt");

        // 子技能执行详情列表
        Object subTaskResultsRaw = body.get("subTaskResults");
        if (subTaskResultsRaw instanceof java.util.Collection) {
            req.subTaskResults = new java.util.ArrayList<>();
            for (Object item : (java.util.Collection<?>) subTaskResultsRaw) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) item;
                    BxdcbotRunCompletionService.SubTaskResult sr = new BxdcbotRunCompletionService.SubTaskResult();
                    sr.skillName = (String) m.get("skillName");
                    sr.status = (String) m.get("status");
                    sr.result = (String) m.get("result");
                    sr.asyncTaskId = m.get("asyncTaskId") instanceof Number
                            ? ((Number) m.get("asyncTaskId")).intValue() : null;
                    sr.completedAt = (String) m.get("completedAt");
                    req.subTaskResults.add(sr);
                }
            }
        }

        if (req.runId == null || req.runId.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "runId is required"));
        }
        if (req.conversationId == null || req.conversationId.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "conversationId is required"));
        }

        // ★ 为 subTaskResults 里没有 asyncTaskId 的 sync 子任务创建合成通知，
        //   让通知中心能看到 Bxdcbot 调用的所有子任务（包括 sync）。
        if (req.subTaskResults != null && req.parentToolId != null) {
            for (BxdcbotRunCompletionService.SubTaskResult sr : req.subTaskResults) {
                if (sr.asyncTaskId != null) continue;  // async 子任务已独立产生通知行
                // sync 子任务：合成通知
                Long skillId = skillService.getSkillByName(sr.skillName)
                        .map(Skill::getId).orElse(null);
                try {
                    asyncTaskPollingService.createBxdcbotSubtaskNotification(
                            req.userId,
                            req.conversationId,
                            sr.skillName,
                            req.parentToolId,  // 这里 parentToolId = outer Bxdcbot call id
                            req.parentSkillId,
                            skillId,
                            sr.result,
                            null);
                } catch (Exception e) {
                    log.warn("[BxdcbotRunCompletion] Failed to create synthetic notification for sub-task {}: {}",
                            sr.skillName, e.getMessage());
                }
            }
        }

        // fire-and-forget：调 service 的 @Async 方法，立即返回 200
        bxdcbotRunCompletionService.onRunComplete(req);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accepted", true);
        resp.put("runId", req.runId);
        resp.put("status", req.status);
        log.info("[BxdcbotRunCompletion] Accepted run complete callback: runId={}, status={}, conversationId={}",
                req.runId, req.status, req.conversationId);
        return ResponseEntity.ok(resp);
    }
}
