/**
 * Gateway 压缩客户端（agent-core 侧）
 *
 * open spec: llm-context-window-summarization
 *
 * 职责：把 LangChain 的 BaseMessage[] 序列化成 OpenAI 兼容 JSON，HTTP POST
 * 到 gateway 的 /api/conversations/{id}/compact 端点，再把返回的 JSON
 * 反序列化成 BaseMessage[]。
 *
 * 严格最小：
 * - 1 个新文件（本文件）
 * - agent.controller.ts 中 1 行调用（await compactClient.compact(...)）
 * - 不新增依赖（用项目自带的 axios / fetch）
 * - 不新增 env var（除可能复用 INTERNAL_API_TOKEN）
 *
 * 失败 fallback：捕获所有异常（HTTP error / timeout / parse error），
 * 返回原始 input messages，不阻塞主 chat 流水线。
 */

import { AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage } from '@langchain/core/messages';

interface CompactWireMessage {
  role: string;
  content?: unknown;
  tool_calls?: unknown;
  tool_call_id?: string;
  name?: string;
  source?: string;
  [k: string]: unknown;
}

interface CompactRequestBody {
  userId: string | null;
  messages: CompactWireMessage[];
  model: string;
}

interface CompactResponseBody {
  messages: CompactWireMessage[];
  summaryApplied: boolean;
  summarySource: string;
}

const DEFAULT_TIMEOUT_MS = 3000;

export class GatewayCompactClient {
  private readonly gatewayBaseUrl: string;
  private readonly internalToken: string | undefined;
  private readonly timeoutMs: number;

  constructor(opts?: { gatewayBaseUrl?: string; internalToken?: string; timeoutMs?: number }) {
    this.gatewayBaseUrl =
      opts?.gatewayBaseUrl ??
      process.env.JAVA_GATEWAY_URL ??
      'http://localhost:18080';
    this.internalToken = opts?.internalToken ?? process.env.INTERNAL_API_TOKEN;
    this.timeoutMs = opts?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  /**
   * 调一次 gateway 压缩。失败/超时/降级一律返回原始 messages，不抛错。
   */
  async compact(
    conversationId: string,
    userId: string | null | undefined,
    messages: BaseMessage[],
    model: string | undefined,
  ): Promise<BaseMessage[]> {
    if (!conversationId || !Array.isArray(messages) || messages.length === 0) {
      return messages;
    }
    const wireMessages = messages.map((m) => this.toWire(m));
    const body: CompactRequestBody = {
      userId: userId ?? null,
      messages: wireMessages,
      model: model ?? '',
    };

    const url = `${this.gatewayBaseUrl.replace(/\/+$/, '')}/api/conversations/${encodeURIComponent(
      conversationId,
    )}/compact`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (this.internalToken) headers['X-Internal-Token'] = this.internalToken;

      const resp = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!resp.ok) {
        this.recordError(resp.status >= 500 ? '5xx' : '4xx');
        console.warn(
          `[GatewayCompactClient] gateway compact returned HTTP ${resp.status}, using original messages`,
        );
        return messages;
      }
      const data = (await resp.json()) as CompactResponseBody;
      if (!data || !Array.isArray(data.messages)) {
        this.recordError('parse');
        console.warn('[GatewayCompactClient] invalid response shape, using original messages');
        return messages;
      }
      console.log(
        `[GatewayCompactClient] convId=${conversationId} summaryApplied=${data.summaryApplied} source=${data.summarySource} in=${messages.length} out=${data.messages.length}`,
      );
      return data.messages.map((m) => this.fromWire(m));
    } catch (e: any) {
      const kind = e?.name === 'AbortError' ? 'timeout' : 'network';
      this.recordError(kind);
      console.warn(
        `[GatewayCompactClient] compact failed (${e?.name || 'error'}: ${e?.message}), using original messages`,
      );
      return messages;
    } finally {
      clearTimeout(timer);
    }
  }

  /** BaseMessage → OpenAI 兼容 JSON */
  private toWire(m: BaseMessage): CompactWireMessage {
    const out: CompactWireMessage = { role: this.normalizeRole(m) };
    if (typeof m.content === 'string') {
      out.content = m.content;
    } else if (Array.isArray(m.content)) {
      out.content = m.content
        .map((p: any) => (typeof p === 'string' ? p : p?.text ?? ''))
        .join('');
    } else {
      out.content = '';
    }
    const tc = (m as any).tool_calls;
    if (Array.isArray(tc) && tc.length > 0) out.tool_calls = tc;
    const tcid = (m as any).tool_call_id;
    if (typeof tcid === 'string') out.tool_call_id = tcid;
    const name = (m as any).name;
    if (typeof name === 'string') out.name = name;
    const src = (m as any).additional_kwargs?.source ?? (m as any).source;
    if (typeof src === 'string') out.source = src;
    return out;
  }

  /** OpenAI 兼容 JSON → BaseMessage */
  private fromWire(m: CompactWireMessage): BaseMessage {
    const role = (m.role || '').toLowerCase();
    const content = typeof m.content === 'string' ? m.content : '';
    if (role === 'system') {
      const msg = new SystemMessage(content);
      if (m.source) (msg as any).additional_kwargs = { ...(msg as any).additional_kwargs, source: m.source };
      return msg;
    }
    if (role === 'tool') {
      return new ToolMessage({ content, tool_call_id: m.tool_call_id || '', name: m.name || '' });
    }
    if (role === 'assistant' || role === 'ai') {
      const msg = new AIMessage(content);
      if (Array.isArray(m.tool_calls)) (msg as any).tool_calls = m.tool_calls;
      if (m.source) (msg as any).additional_kwargs = { ...(msg as any).additional_kwargs, source: m.source };
      return msg;
    }
    // user / human / 其它都归到 human
    return new HumanMessage(content);
  }

  private normalizeRole(m: BaseMessage): string {
    const t = (m as any).getType?.() ?? (m as any)._getType?.() ?? (m as any).type ?? '';
    if (t === 'human') return 'user';
    if (t === 'ai') return 'assistant';
    if (t === 'system') return 'system';
    if (t === 'tool') return 'tool';
    return 'user';
  }

  /** 错误计数器（agent_core_compact_client_errors_total{kind=...}） */
  private static errorCounts: Record<string, number> = {};
  private recordError(kind: '4xx' | '5xx' | 'timeout' | 'network' | 'parse') {
    GatewayCompactClient.errorCounts[kind] = (GatewayCompactClient.errorCounts[kind] ?? 0) + 1;
  }

  /** 暴露错误计数（用于测试 / 监控） */
  static getErrorCounts(): Record<string, number> {
    return { ...this.errorCounts };
  }
  static resetErrorCounts() {
    this.errorCounts = {};
  }
}

/** 默认单例（方便 controller 直接用 GatewayCompactClient.instance.compact(...)） */
export const gatewayCompactClient = new GatewayCompactClient();
