/**
 * BxdcbotRun resumer：所有 pending async 完成后重新 invoke Bxdcbot 子规划。
 *
 * open spec: bxdcbot-multi-turn-async（决策 4）
 *
 * 职责：
 * - 在 BxdcbotRunScheduler.processRun 末尾调用
 * - 把 run.status 置回 running
 * - 重新进入 6 轮子规划周期（让 LLM 看到累积的 async 真结果）
 * - 注意：实际 executeOpenClawSkill 是同步的，会跑完整个 6 轮子规划后才返回
 *   （这里调用是 fire-and-forget）
 *
 * 重要：MVP 阶段 executeOpenClawSkill 是 **同一函数多周期调用**——
 * 第一周期检测到 async 时提前 return 占位结果，然后 resume 进入第二周期
 * （LLM 看到累积的 async 真结果）。这个 resumer 实际触发**新一轮的
 * executeOpenClawSkill 调用**，传入上次累积的 run.messages。
 *
 * 实现：调用 `resumeBxdcbotPlanner(run)` 函数（由 executeOpenClawSkill 的多周期改造
 * 部分导出），把 run 传进去。
 */

import { BxdcbotRun, globalBxdcbotRunStore } from "./bxdcbot-run-store";
import { resumeBxdcbotPlanner } from "../tools/java-skills";

export async function resumeBxdcbotRun(run: BxdcbotRun): Promise<void> {
  console.log(`[BxdcbotRunResumer] Resuming runId=${run.runId} (round=${run.currentRound + 1})`);

  // 置回 running
  globalBxdcbotRunStore.update(run.runId, {
    status: "running",
    currentRound: run.currentRound + 1,
  });

  const fresh = globalBxdcbotRunStore.get(run.runId);
  if (!fresh) return;

  try {
    const result = await resumeBxdcbotPlanner(fresh);

    // 续答结果可能是：
    // 1. 调了新的 async → 状态会再次置 awaiting_async，resumer 不该处理
    // 2. 没调 async，跑完了 → 状态变 completed
    // 3. 60 轮触顶 → 状态变 failed
    const finalRun = globalBxdcbotRunStore.get(fresh.runId);
    if (finalRun && finalRun.status === "completed") {
      // run 跑完 → 调 complete API 回灌对话流
      const { notifyBxdcbotRunComplete } = await import("./bxdcbot-run-notifier.js");
      await notifyBxdcbotRunComplete(finalRun);
    } else if (finalRun && finalRun.status === "failed") {
      // 60 轮触顶 → 也调 complete 回灌（failed 路径）
      const { notifyBxdcbotRunComplete } = await import("./bxdcbot-run-notifier.js");
      await notifyBxdcbotRunComplete(finalRun);
    }
    // awaiting_async 状态不处理（scheduler 下个 tick 会处理）
  } catch (e: any) {
    console.error(`[BxdcbotRunResumer] resumeBxdcbotPlanner failed for runId=${fresh.runId}:`, e);
    globalBxdcbotRunStore.update(fresh.runId, {
      status: "failed",
      failureReason: `Resume failed: ${e?.message}`,
      finishedAt: new Date(),
    });
    const failed = globalBxdcbotRunStore.get(fresh.runId);
    if (failed) {
      const { notifyBxdcbotRunComplete } = await import("./bxdcbot-run-notifier.js");
      await notifyBxdcbotRunComplete(failed);
    }
  }
}
