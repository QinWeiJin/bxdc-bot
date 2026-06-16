/**
 * BxdcbotRunScheduler 后台调度器。
 *
 * open spec: bxdcbot-multi-turn-async（决策 4 + 决策 11）
 *
 * 职责：
 * - 每 2 秒扫一次 awaiting_async 状态的 BxdcbotRun
 * - 对每个 run，调 gateway `/api/async-tasks/{id}/wait` 阻塞监听每个 pending async
 * - async 终态时把真结果作为 tool 消息注入 BxdcbotRun.messages
 *   - 成功 → 注入真结果 + 清理 skillRetries
 *   - 失败 → 走决策 11 失败隔离（重试 N 次，N 次全失败则硬终止 Bxdcbot run）
 * - 所有 pending 完成 / 60 轮触顶 → 重新 invoke `executeOpenClawSkill` 进入新周期
 *
 * 关键设计：
 * - 重试在 LLM 外：scheduler 自己重试，**不**调 LLM
 * - 重试对 LLM 透明：LLM 只看到"最终结果"或"run terminated"——**不**看到中间 N 次失败
 * - 重试不消耗 60 轮：重试是同步等待，**不**走 LLM 调用
 * - 失败结果 MUST NOT 注入 messages：避免错误传染
 *
 * 实现：用 Node.js `setInterval` 实现轮询（不用 @nestjs/schedule，遵循 AGENTS.md 5.1 不新增第三方包）。
 */

import { Injectable, OnModuleInit, OnModuleDestroy } from "@nestjs/common";
import { globalBxdcbotRunStore, BxdcbotRun } from "./bxdcbot-run-store";
import { resumeBxdcbotRun } from "./bxdcbot-run-resumer";

const MAX_RETRIES = parseInt(process.env.BXDCBOT_SKILL_MAX_RETRIES || "3", 10);
/**
 * 异步任务完成 → 调 LLM 续周期的延迟控制。
 *
 * 链路：每个 tick 都对每个 pending task 调 gateway `/api/async-tasks/{id}/wait?timeout=N`，
 * gateway 端以 1s 一次 DB 轮询。所以"完成→resume"最坏延迟 ≈ POLL_INTERVAL_MS + 1s。
 *
 * 调小 POLL_INTERVAL_MS → tick 更频繁（更短的"盲区"窗口）
 * 调大 WAIT_TIMEOUT_SEC → 单次 wait 阻塞更久，等待结束即返回（gateway 每 1s 一次 poll，1s 内可探到完成）
 *
 * 当前取值：500ms tick + 5s wait + 6s AbortSignal → 最坏 ~1.5s 完成检测，
 * 相比原来的 2s tick + 2s wait + 3s AbortSignal（最坏 ~3s）提速 50%。
 *
 * 改了没有新增 env，符合 AGENTS.md 5.2 不新增环境变量约束。
 */
const POLL_INTERVAL_MS = 500;
const WAIT_TIMEOUT_SEC = 5;
const WAIT_ABORT_MS = 6000;

@Injectable()
export class BxdcbotRunScheduler implements OnModuleInit, OnModuleDestroy {
  private timer: NodeJS.Timeout | null = null;
  // 标记正在处理的 runId（避免重入）
  private readonly inFlight = new Set<string>();

  onModuleInit() {
    this.timer = setInterval(() => {
      this.tick().catch((e) => console.error("[BxdcbotRunScheduler] tick failed:", e));
    }, POLL_INTERVAL_MS);
    console.log(`[BxdcbotRunScheduler] Started, polling every ${POLL_INTERVAL_MS}ms, wait timeout=${WAIT_TIMEOUT_SEC}s, max retries=${MAX_RETRIES}`);
  }

  onModuleDestroy() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
      console.log("[BxdcbotRunScheduler] Stopped");
    }
  }

  async tick() {
    const awaitingRuns = globalBxdcbotRunStore.listByStatus("awaiting_async");
    if (awaitingRuns.length === 0) return;

    console.log(`[BxdcbotRunScheduler] Tick: ${awaitingRuns.length} runs awaiting async`);

    for (const run of awaitingRuns) {
      // 避免同一 run 被多个 tick 重入
      if (this.inFlight.has(run.runId)) continue;
      this.inFlight.add(run.runId);

      try {
        await this.processRun(run);
      } catch (e: any) {
        console.error(`[BxdcbotRunScheduler] processRun failed for runId=${run.runId}:`, e);
      } finally {
        this.inFlight.delete(run.runId);
      }
    }
  }

  /**
   * 处理一个 awaiting_async 的 Bxdcbot run。
   * 对每个 pending async task 并行阻塞 wait；任一完成时检查终态：
   * - SUCCESS → 注入 tool result
   * - FAILED / TIMEOUT → 决策 11 失败隔离
   * 全完成时 resume 进新周期。
   */
  private async processRun(run: BxdcbotRun): Promise<void> {
    const pendingIds = Array.from(run.pendingAsyncTaskIds);
    if (pendingIds.length === 0) {
      console.warn(`[BxdcbotRunScheduler] runId=${run.runId} has status=awaiting_async but no pending tasks — force resume`);
      await resumeBxdcbotRun(run);
      return;
    }

    console.log(`[BxdcbotRunScheduler] runId=${run.runId} waiting on ${pendingIds.length} async task(s): [${pendingIds.join(", ")}]`);

    // 对每个 pending task 并行 wait
    let settledCount = 0;
    const waitPromises = pendingIds.map(async (asyncTaskId) => {
      const settled = await this.waitForAsyncTask(run, asyncTaskId);
      if (settled) settledCount++;
    });
    await Promise.all(waitPromises);

    if (settledCount === 0) return; // 无一终态，下个 tick 再试

    // 重新读 run 状态（wait 过程中可能被改了）
    const fresh = globalBxdcbotRunStore.get(run.runId);
    if (!fresh) {
      console.warn(`[BxdcbotRunScheduler] runId=${run.runId} disappeared from store, skip resume`);
      return;
    }
    if (fresh.status !== "awaiting_async") {
      console.log(`[BxdcbotRunScheduler] runId=${run.runId} status changed to ${fresh.status} during wait, skip resume`);
      return;
    }

    // 60 轮触顶检查
    if (fresh.currentRound >= 60) {
      console.error(`[BxdcbotRunScheduler] runId=${run.runId} hit 60-round cap, terminating`);
      globalBxdcbotRunStore.update(fresh.runId, {
        status: "failed",
        failureReason: `60 轮触顶（跑了 ${fresh.currentRound} 轮）`,
        finishedAt: new Date(),
      });
      const terminated = globalBxdcbotRunStore.get(fresh.runId);
      if (terminated) await this.notifyRunComplete(terminated);
      return;
    }

    // 全完成 → resume
    console.log(`[BxdcbotRunScheduler] runId=${run.runId} all ${pendingIds.length} async task(s) settled, resuming`);
    await resumeBxdcbotRun(fresh);
  }

  /**
   * 等一个 async task 完成 / 失败 / 超时。
   * 终态时根据 status 走不同路径（成功注入 / 失败重试 / 硬终止）。
   * @returns true = 已终态并处理完毕；false = 未终态（404 / PENDING / POLLING）
   */
  private async waitForAsyncTask(run: BxdcbotRun, asyncTaskId: number): Promise<boolean> {
    const gatewayBaseUrl = process.env.SKILL_GATEWAY_URL || "http://localhost:18080";
    const internalToken = process.env.INTERNAL_API_TOKEN || "";

    const url = `${gatewayBaseUrl}/api/async-tasks/${asyncTaskId}/wait?timeout=${WAIT_TIMEOUT_SEC}`;
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (internalToken) headers["X-Internal-Token"] = internalToken;

    let result: any;
    try {
      // AbortSignal 比 wait timeout 多 1s buffer，给 gateway 把 response 写回来
      const resp = await fetch(url, { method: "GET", headers, signal: AbortSignal.timeout(WAIT_ABORT_MS) });
      if (!resp.ok) {
        console.warn(`[BxdcbotRunScheduler] wait HTTP ${resp.status} for taskId=${asyncTaskId}`);
        return false;
      }
      result = await resp.json();
    } catch (e: any) {
      console.warn(`[BxdcbotRunScheduler] wait failed for taskId=${asyncTaskId}: ${e?.message}`);
      return false;
    }

    const status = result?.status;
    if (status !== "SUCCESS" && status !== "COMPLETED" && status !== "FAILED" && status !== "TIMEOUT") {
      return false;  // 还在跑，下个 tick 再试
    }

    // 终态：处理
    const fresh = globalBxdcbotRunStore.get(run.runId);
    if (!fresh) return false;

    const skillName = this.findSkillNameByAsyncTaskId(fresh, asyncTaskId);

    if (status === "SUCCESS" || status === "COMPLETED") {
      // 成功路径：注入真结果
      this.injectToolResult(fresh, asyncTaskId, result);
      globalBxdcbotRunStore.unregisterAsyncTask(fresh.runId, skillName, asyncTaskId, true);
      console.log(`[BxdcbotRunScheduler] taskId=${asyncTaskId} SUCCESS, injected to runId=${fresh.runId}`);
      return true;
    } else {
      // 失败 / 超时 → 决策 11 失败隔离
      await this.handleSkillFailure(fresh, skillName, asyncTaskId, status, result?.errorMessage || result?.error || "");
      return true;
    }
  }

  /**
   * 决策 11：失败隔离 + 自动重试 + N 次后硬终止。
   */
  private async handleSkillFailure(
    run: BxdcbotRun,
    skillName: string,
    asyncTaskId: number,
    terminalStatus: string,
    errorMessage: string,
  ): Promise<void> {
    const retriesSoFar = run.skillRetries.get(skillName) || 0;

    if (retriesSoFar < MAX_RETRIES) {
      // 重试路径
      globalBxdcbotRunStore.incrementRetry(run.runId, skillName);
      console.warn(`[BxdcbotRunScheduler] taskId=${asyncTaskId} ${terminalStatus}, retrying (${retriesSoFar + 1}/${MAX_RETRIES}) skill=${skillName} runId=${run.runId}: ${errorMessage}`);

      // 从 originalSkillArgs 拿原 args（决策 11 复用同 args 重试）
      const originalArgs = run.originalSkillArgs.get(`${skillName}:${asyncTaskId}`);

      // 调 gateway 重新发起同 args 同 payload 的新 task
      const newTaskId = await this.retrySkill(run, skillName, originalArgs);
      if (newTaskId == null) {
        console.error(`[BxdcbotRunScheduler] retry submit failed for skill=${skillName}, terminating runId=${run.runId}`);
        this.terminateRun(run, `${skillName} 重试发起失败：${errorMessage}`);
        return;
      }

      // 注销旧 async task + 注册新 async task
      globalBxdcbotRunStore.unregisterAsyncTask(run.runId, skillName, asyncTaskId, false);
      const newToolCallId = `bxdcbot-retry-${newTaskId}`;
      globalBxdcbotRunStore.registerAsyncTask(run.runId, skillName, newTaskId, newToolCallId, originalArgs);

      console.log(`[BxdcbotRunScheduler] taskId=${asyncTaskId} → newTaskId=${newTaskId} (retry ${retriesSoFar + 1}/${MAX_RETRIES})`);
    } else {
      // N 次全失败 → 硬终止 Bxdcbot run
      console.error(`[BxdcbotRunScheduler] taskId=${asyncTaskId} failed ${MAX_RETRIES} times, terminating runId=${run.runId} skill=${skillName}: ${errorMessage}`);
      this.terminateRun(run, `${skillName} 失败 ${MAX_RETRIES} 次后终止：${errorMessage}`);
    }
  }

  /**
   * 重新发起同 args 同 payload 的新 task（决策 11 重试）。
   * NOTE: skillId 解析由 agent-core 内部 skillId 映射完成（从 availableTools 找），
   * 这里简化用 skillName 作为参数；实际生产需通过 SKILL 名称查表得 skillId。
   */
  private async retrySkill(run: BxdcbotRun, skillName: string, originalArgs: any): Promise<number | null> {
    const gatewayBaseUrl = process.env.SKILL_GATEWAY_URL || "http://localhost:18080";
    const internalToken = process.env.INTERNAL_API_TOKEN || "";

    const url = `${gatewayBaseUrl}/api/skills/execute`;
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (internalToken) headers["X-Internal-Token"] = internalToken;
    if (run.userId) headers["X-User-Id"] = run.userId;

    const body = {
      skillName,
      parameters: originalArgs,
      parentToolId: run.parentToolId,
      parentSkillId: run.parentSkillId,
    };

    try {
      const resp = await fetch(url, {
        method: "POST", headers,
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(5000),
      });
      if (!resp.ok) return null;
      const result = await resp.json();
      return result?.asyncTaskId ?? null;
    } catch (e: any) {
      console.error(`[BxdcbotRunScheduler] retrySkill failed: ${e?.message}`);
      return null;
    }
  }

  /**
   * 注入 tool 真结果到 BxdcbotRun.messages。
   * 用 asyncTaskIdToToolCallId 反查 tool_call_id（漏洞 3 修复）。
   */
  private injectToolResult(run: BxdcbotRun, asyncTaskId: number, result: any): void {
    const toolCallId = run.asyncTaskIdToToolCallId.get(asyncTaskId);
    if (!toolCallId) {
      console.warn(`[BxdcbotRunScheduler] no tool_call_id mapping for asyncTaskId=${asyncTaskId}, skip inject`);
      return;
    }
    // 替换占位 tool 消息（不新增第二条）—— OpenAI/LangChain 要求一个 tool_call_id 对应一条 tool 消息
    const placeholderIndex = run.messages.findIndex(
      (m: any) => m.role === "tool" && m.tool_call_id === toolCallId,
    );
    // 从 wait 端点返回的包装里提取真结果，让 LLM 看到跟 sync skill 一致的格式
    const innerResult = result?.result || result;
    const realContent = typeof innerResult === "string" ? innerResult : JSON.stringify(innerResult);
    if (placeholderIndex >= 0) {
      run.messages[placeholderIndex] = {
        role: "tool",
        tool_call_id: toolCallId,
        content: realContent,
      };
      console.log(`[BxdcbotRunScheduler] replaced placeholder for taskId=${asyncTaskId} tool_call_id=${toolCallId} at index=${placeholderIndex}, realContent_len=${realContent.length}`);
    } else {
      run.messages.push({
        role: "tool",
        tool_call_id: toolCallId,
        content: realContent,
      });
      console.log(`[BxdcbotRunScheduler] injected tool result for taskId=${asyncTaskId} tool_call_id=${toolCallId} (no placeholder found)`);
    }

    // 更新子技能结果追踪
    const subIdx = run.subTaskResults.findIndex((s) => s.asyncTaskId === asyncTaskId);
    if (subIdx >= 0) {
      run.subTaskResults[subIdx] = {
        ...run.subTaskResults[subIdx],
        status: "completed",
        result: realContent,
        completedAt: new Date().toISOString(),
      };
    }
  }

  /**
   * 硬终止 Bxdcbot run + 调 gateway complete 回灌。
   */
  private terminateRun(run: BxdcbotRun, failureReason: string): void {
    globalBxdcbotRunStore.update(run.runId, {
      status: "failed",
      failureReason,
      finishedAt: new Date(),
    });
    const fresh = globalBxdcbotRunStore.get(run.runId);
    if (fresh) {
      this.notifyRunComplete(fresh).catch((e) =>
        console.warn(`[BxdcbotRunScheduler] notifyRunComplete failed: ${e?.message}`)
      );
    }
  }

  private async notifyRunComplete(run: BxdcbotRun): Promise<void> {
    const { notifyBxdcbotRunComplete } = await import("./bxdcbot-run-notifier.js");
    await notifyBxdcbotRunComplete(run);
  }

  private findSkillNameByAsyncTaskId(run: BxdcbotRun, asyncTaskId: number): string {
    const key = `${asyncTaskId}`;
    for (const k of Array.from(run.originalSkillArgs.keys())) {
      if (k.endsWith(`:${key}`)) {
        return k.substring(0, k.length - key.length - 1);
      }
    }
    return "unknown_skill";
  }
}
