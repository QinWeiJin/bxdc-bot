package com.lobsterai.skillgateway.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对话上下文压缩 metrics 埋点（open spec: llm-context-window-summarization）
 *
 * 设计：纯 in-memory 计数器（无 Prometheus/Micrometer 依赖）。
 * 用法：{@link com.lobsterai.skillgateway.service.ConversationCompactService} 在关键
 *      节点调 incXxx()；外部可以通过 /admin/metrics 端点读 snapshot。
 *
 * 指标（spec §9.2）：
 * - context_compaction_total{result=hit|miss|fallback, source=user|system}
 * - context_compaction_pre_recent_size（最近一次 pre-recent 大小）
 * - context_l2_turns（最近一次 L2 轮数）
 * - context_compaction_latency_ms（最近一次压缩耗时）
 *
 * 注：完整 Prometheus 接入是后续工作；本 change 先用 in-memory 计数 + 结构化日志，
 *     用户的 ELK / Loki 抓走 structured log 即可生成 dashboard。
 */
@Component
public class CompactionMetrics {

    /** total = result 维度（hit / miss / fallback）× source 维度（user / system / cache / disabled） */
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private final AtomicLong lastPreRecentSize = new AtomicLong(0);
    private final AtomicLong lastL2Turns = new AtomicLong(0);
    private final AtomicLong lastLatencyMs = new AtomicLong(0);
    private final AtomicLong lastOutputChars = new AtomicLong(0);

    public void incResult(String result, String source) {
        counters.computeIfAbsent("result:" + result + ":source:" + source, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordPreRecentSize(long size) { lastPreRecentSize.set(size); }
    public void recordL2Turns(long turns) { lastL2Turns.set(turns); }
    public void recordLatencyMs(long ms) { lastLatencyMs.set(ms); }
    public void recordOutputChars(long chars) { lastOutputChars.set(chars); }

    /** 导出 snapshot（用于 /admin/metrics 端点或日志打印） */
    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.counters.putAll(counters);
        s.lastPreRecentSize = lastPreRecentSize.get();
        s.lastL2Turns = lastL2Turns.get();
        s.lastLatencyMs = lastLatencyMs.get();
        s.lastOutputChars = lastOutputChars.get();
        return s;
    }

    public static class Snapshot {
        public final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
        public long lastPreRecentSize;
        public long lastL2Turns;
        public long lastLatencyMs;
        public long lastOutputChars;

        public String toStructuredLog() {
            StringBuilder sb = new StringBuilder();
            sb.append("metrics ");
            counters.forEach((k, v) -> {
                sb.append(k).append("=").append(v.get()).append(" ");
            });
            sb.append("last_pre_recent_size=").append(lastPreRecentSize).append(" ");
            sb.append("last_l2_turns=").append(lastL2Turns).append(" ");
            sb.append("last_latency_ms=").append(lastLatencyMs).append(" ");
            sb.append("last_output_chars=").append(lastOutputChars);
            return sb.toString();
        }
    }
}
