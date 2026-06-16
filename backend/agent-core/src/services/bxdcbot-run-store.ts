/**
 * BxdcbotRun 状态机持久化（in-memory Map）。
 *
 * open spec: bxdcbot-multi-turn-async（决策 2）
 *
 * 职责：
 * - 维护所有正在跑的 Bxdcbot run 状态（messages / pendingAsyncTaskIds / currentRound / status / ...）
 * - 跨子规划周期保留上下文（agent-core 进程内 Map）
 * - 6 轮子规划周期内：run.status = running
 * - 调 async skill 时：run.status = awaiting_async，pendingAsyncTaskIds 记录
 * - run 终结时：run.status = completed / failed / timeout
 *
 * MVP 阶段：in-memory，不持久化。agent-core 重启即丢，由外层 LLM 续答兜底。
 * 后续 DB 持久化（用户已确认 MVP 阶段 in-memory）。
 *
 * 关键字段语义：
 * - pendingAsyncTaskIds: Set<number>，N=1 时 size=1，N=5-10 时 size=5..10
 *   scheduler 处理时**不关心** size 是 1 还是 10（用户要求"1-10 统一处理"）
 * - asyncTaskIdToToolCallId: Map<number, string>，asyncTaskId → tool_call_id 关联
 *   漏洞 3 修复：async 完成后注入 messages 时必须用同一个 tool_call_id，LLM 才能正确关联
 * - skillRetries: Map<string, number>，决策 11：每 skill 重试次数
 * - originalSkillArgs: Map<string, any>，决策 11：原 skill args，重试时复用
 * - plannerContext: 决策 4 跨周期所需参数（plannerModel / config / availableTools / parentToolName）
 *   这些是 executeOpenClawSkill 第一周期入参，resumer 重新进入第二周期需要它们
 */

import { randomUUID } from "crypto";

export type BxdcbotRunStatus = "running" | "awaiting_async" | "completed" | "failed" | "timeout";

export interface BxdcbotPlannerContext {
  plannerModel: any;
  parentToolName: string;
  input: string;
  config: any;  // ExtendedSkillConfig from java-skills.ts
  availableTools: any[];
}

export interface BxdcbotRun {
  runId: string;                          // UUID v4
  conversationId: string;
  userId: string;
  parentToolId: string;                   // 外层 tool call_id（也用作 async_tasks.parent_tool_id）
  parentSkillId: number;                  // Bxdcbot skill_id
  messages: any[];                        // LangChain messages 累积
  pendingAsyncTaskIds: Set<number>;       // 当前 6 轮周期待等的 async
  asyncTaskIdToToolCallId: Map<number, string>;  // 漏洞 3 修复
  skillRetries: Map<string, number>;      // 决策 11 失败隔离
  originalSkillArgs: Map<string, any>;    // 决策 11 失败隔离
  currentRound: number;                   // 跨周期计数（不超过 60）
  totalLlmCalls: number;                  // 总 LLM 调用次数
  startedAt: Date;
  finishedAt?: Date;
  status: BxdcbotRunStatus;
  result?: string;                        // 最终 LLM 输出（completed 时填）
  failureReason?: string;                 // failed / timeout 时填
  subTaskSummary?: {                      // run 终结时统计
    total: number;
    succeeded: number;
    failed: number;
    pending: number;
  };
  /** 跨周期所需上下文（resumer 用） */
  plannerContext?: BxdcbotPlannerContext;
  /** 主 skill 名称（用于卡片头部展示） */
  parentSkillName?: string;
  /** 子技能执行结果列表（用于 BXDCBOT_RUN_RESULT 消息展示） */
  subTaskResults: Array<{
    skillName: string
    status: string
    result?: string
    asyncTaskId?: number
    completedAt?: string
  }>;
}

export class BxdcbotRunStore {
  private readonly runs = new Map<string, BxdcbotRun>();

  create(init: {
    conversationId: string;
    userId: string;
    parentToolId: string;
    parentSkillId: number;
    parentSkillName?: string;
    plannerContext: BxdcbotPlannerContext;
  }): BxdcbotRun {
    const run: BxdcbotRun = {
      runId: randomUUID(),
      conversationId: init.conversationId,
      userId: init.userId,
      parentToolId: init.parentToolId,
      parentSkillId: init.parentSkillId,
      parentSkillName: init.parentSkillName,
      messages: [],
      pendingAsyncTaskIds: new Set(),
      asyncTaskIdToToolCallId: new Map(),
      skillRetries: new Map(),
      originalSkillArgs: new Map(),
      currentRound: 0,
      totalLlmCalls: 0,
      startedAt: new Date(),
      status: "running",
      plannerContext: init.plannerContext,
      subTaskResults: [],
    };
    this.runs.set(run.runId, run);
    return run;
  }

  get(runId: string): BxdcbotRun | undefined {
    return this.runs.get(runId);
  }

  update(runId: string, patch: Partial<BxdcbotRun>): BxdcbotRun | undefined {
    const existing = this.runs.get(runId);
    if (!existing) return undefined;
    const updated = { ...existing, ...patch };
    this.runs.set(runId, updated);
    return updated;
  }

  delete(runId: string): boolean {
    return this.runs.delete(runId);
  }

  listByStatus(status: BxdcbotRunStatus): BxdcbotRun[] {
    const out: BxdcbotRun[] = [];
    for (const run of this.runs.values()) {
      if (run.status === status) out.push(run);
    }
    return out;
  }

  listAll(): BxdcbotRun[] {
    return Array.from(this.runs.values());
  }

  size(): number {
    return this.runs.size;
  }

  /**
   * 记录子 async task（决策 3 + 漏洞 3 修复）。
   */
  registerAsyncTask(runId: string, skillName: string, asyncTaskId: number, toolCallId: string, args: any): void {
    const run = this.runs.get(runId);
    if (!run) return;
    run.pendingAsyncTaskIds.add(asyncTaskId);
    run.asyncTaskIdToToolCallId.set(asyncTaskId, toolCallId);
    run.originalSkillArgs.set(`${skillName}:${asyncTaskId}`, args);
  }

  /**
   * 子 async task 完成 / 失败时调用。
   */
  unregisterAsyncTask(runId: string, skillName: string, asyncTaskId: number, success: boolean): void {
    const run = this.runs.get(runId);
    if (!run) return;
    run.pendingAsyncTaskIds.delete(asyncTaskId);
    run.asyncTaskIdToToolCallId.delete(asyncTaskId);
    run.originalSkillArgs.delete(`${skillName}:${asyncTaskId}`);
    if (success) {
      run.skillRetries.delete(skillName);
    }
  }

  /**
   * 记录 skill 重试次数（决策 11）。
   */
  incrementRetry(runId: string, skillName: string): number {
    const run = this.runs.get(runId);
    if (!run) return 0;
    const current = run.skillRetries.get(skillName) || 0;
    const next = current + 1;
    run.skillRetries.set(skillName, next);
    return next;
  }
}

/** 全局单例（agent-core 进程内） */
export const globalBxdcbotRunStore = new BxdcbotRunStore();
