/**
 * Bxdcbot run 终态 callback 客户端。
 *
 * open spec: bxdcbot-multi-turn-async（决策 10 / §17）
 *
 * 职责：Bxdcbot run 跑完（completed / failed / 60 轮触顶 / 全局超时）时，
 * 调 gateway 内部 API `POST /api/internal/bxdcbot-run/complete`，
 * 让 gateway 写 BXDCBOT_RUN_RESULT 对话消息 + 推 SSE + 触发外层 LLM 续答。
 *
 * fire-and-forget 模式：5s 超时 + try/catch + 只记 log，不抛回 Bxdcbot run 终结流程。
 *
 * 关键设计：用户要求"回灌会话的时候不要影响之前设计的异步和长调用逻辑"——
 * 本服务**不修改** `gateway-compact-client.ts`（那是 LLM 压缩路径），
 * 也不修改普通 async 续答的 `AsyncTaskChatReplyService` 路径。
 * 是独立的"run 整体结果"回灌通道。
 */

import { BxdcbotRun } from "./bxdcbot-run-store";

const DEFAULT_TIMEOUT_MS = 5000;

export async function notifyBxdcbotRunComplete(run: BxdcbotRun): Promise<void> {
  const gatewayBaseUrl = process.env.SKILL_GATEWAY_URL || "http://localhost:18080";
  const internalToken = process.env.INTERNAL_API_TOKEN || "";

  const url = `${gatewayBaseUrl}/api/internal/bxdcbot-run/complete`;
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (internalToken) headers["X-Internal-Token"] = internalToken;

  // 计算 subTaskSummary（用 pendingAsyncTaskIds / skillRetries 推算）
  // MVP 简化：用 Map size 计数
  const totalSkillsAttempted = (run.skillRetries.size || 0) + 1;  // 简化估算
  const succeeded = run.status === "completed" ? 1 : 0;  // MVP: completed 才算 succeeded
  const failed = run.status === "failed" || run.status === "timeout" ? 1 : 0;
  const subTaskSummary = JSON.stringify({
    total: run.pendingAsyncTaskIds.size + succeeded + failed,
    succeeded,
    failed,
    pending: run.pendingAsyncTaskIds.size,
  });

  // 子技能执行详情（每个子技能的结果，前端展示用）
  const subTaskResults = (run.subTaskResults || []).map((s) => ({
    skillName: s.skillName,
    status: s.status,
    result: s.result ?? null,
    asyncTaskId: s.asyncTaskId ?? null,
    completedAt: s.completedAt ?? null,
  }));

  const body = {
    runId: run.runId,
    conversationId: run.conversationId,
    userId: run.userId,
    parentToolId: run.parentToolId,
    parentSkillId: run.parentSkillId,
    parentSkillName: run.parentSkillName || null,
    status: run.status === "timeout" ? "timeout" : run.status,  // 'completed' | 'failed' | 'timeout'
    finalText: run.result || null,
    failureReason: run.failureReason || null,
    roundsUsed: run.currentRound,
    llmCallsUsed: run.totalLlmCalls,
    subTaskSummary,
    subTaskResults,
    finishedAt: (run.finishedAt || new Date()).toISOString(),
  };

  try {
    const resp = await fetch(url, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
    });
    if (!resp.ok) {
      console.warn(`[BxdcbotRunNotifier] Complete HTTP ${resp.status} for runId=${run.runId}`);
      return;
    }
    console.log(`[BxdcbotRunNotifier] Complete OK for runId=${run.runId}, status=${run.status}`);
  } catch (e: any) {
    console.warn(`[BxdcbotRunNotifier] Complete failed for runId=${run.runId}: ${e?.message}`);
  }
}
