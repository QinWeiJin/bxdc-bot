package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.dto.ConversationSummaryRow;
import com.lobsterai.skillgateway.entity.User;
import com.lobsterai.skillgateway.http.LlmHttpClient;
import com.lobsterai.skillgateway.mapper.UserMapper;
import com.lobsterai.skillgateway.metrics.CompactionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对话上下文压缩服务（gateway 侧）
 *
 * open spec: llm-context-window-summarization
 *
 * 职责：
 * 1. 接收 agent-core 送来的 messages 数组
 * 2. 按 L2 轮数 > 阈值（默认 48）触发摘要
 * 3. 分层：L0（system, 含梦境消息）+ L1（tool/skills/异步结果）永保留
 *    + L2（user/assistant）按 recent 16 保留 / pre-recent 16 全部进压缩链
 * 4. 复用 LlmHttpClient 调 LLM 摘要（用户 LLM → 系统默认 fallback）
 * 5. 摘要结果持久化到 conversation_message_summaries 表（按 range 缓存）
 * 6. 失败/不可用时降级到"暂停压缩"：pre-recent 16 不送 LLM（DB 不动）
 *
 * 输入输出消息格式（OpenAI 兼容）：
 * <pre>
 *   [
 *     {"role": "system", "content": "..."},
 *     {"role": "user", "content": "..."},
 *     {"role": "assistant", "content": "...", "tool_calls": [...]},
 *     {"role": "tool", "tool_call_id": "...", "content": "..."},
 *     ...
 *   ]
 * </pre>
 */
@Service
public class ConversationCompactService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactService.class);

    /** 梦境消息标记（system 消息 content 含此串 = 梦境查询结果，永保留） */
    public static final String DREAM_QUERY_MARKER = "梦境查询完成";

    /** 摘要 prompt 模板（v1） */
    private static final String SUMMARY_PROMPT = String.join("\n",
            "你是一个对话历史压缩助手。",
            "请将以下对话历史压缩成 200-500 字的精炼摘要。",
            "保留：关键事实、用户意图、决策结论、约束条件、待办事项。",
            "**不要**添加评论、**不要**复述 meta 信息、**不要**编造内容。",
            "输出与对话同语言的纯文本。"
    );

    private final ConversationSummaryDao summaryDao;
    private final LlmHttpClient llmHttpClient;
    private final UserService userService;
    private final UserMapper userMapper;
    private final CompactionMetrics metrics;

    /** 可在测试中通过 package-private setter 覆盖 */
    private boolean enabled = readEnvEnabled();
    private int threshold = readEnvThreshold();
    private int recentK = readEnvRecentK();

    private static boolean readEnvEnabled() {
        String v = System.getenv("CONTEXT_COMPACTION_ENABLED");
        return v == null || v.isEmpty() || !"false".equalsIgnoreCase(v.trim());
    }

    private static int readEnvThreshold() {
        String v = System.getenv("CONTEXT_COMPACTION_THRESHOLD");
        if (v == null || v.isEmpty()) return 48;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return 48; }
    }

    private static int readEnvRecentK() {
        String v = System.getenv("CONTEXT_COMPACTION_RECENT_K");
        if (v == null || v.isEmpty()) return 16;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return 16; }
    }

    @Autowired
    public ConversationCompactService(ConversationSummaryDao summaryDao,
                                      LlmHttpClient llmHttpClient,
                                      UserService userService,
                                      UserMapper userMapper,
                                      CompactionMetrics metrics) {
        this.summaryDao = summaryDao;
        this.llmHttpClient = llmHttpClient;
        this.userService = userService;
        this.userMapper = userMapper;
        this.metrics = metrics;
    }

    /** 测试用：覆盖配置 */
    void setConfigForTest(boolean enabled, int threshold, int recentK) {
        this.enabled = enabled;
        this.threshold = threshold;
        this.recentK = recentK;
    }

    /**
     * 压缩入口。
     *
     * @param conversationId 对话 ID（仅用作 cache key）
     * @param userId         用户 ID（用来读 user.llm_config + 归属校验）
     * @param messages       输入消息数组（OpenAI 兼容 role/content/tool_calls/tool_call_id/source）
     * @return CompactResult
     */
    public CompactResult compact(String conversationId, String userId,
                                 List<Map<String, Object>> messages) {
        long startedAtMs = System.currentTimeMillis();
        if (messages == null || messages.isEmpty()) {
            metrics.incResult("empty", "input");
            return new CompactResult(new ArrayList<>(), false, "empty_input");
        }
        if (!enabled) {
            metrics.incResult("disabled", "config");
            return new CompactResult(messages, false, "disabled");
        }

        // 1. 分层
        LayerPartition part = partition(messages);

        // 2. L2 轮数判定
        int l2Turns = countL2Turns(part.l2);
        metrics.recordL2Turns(l2Turns);
        if (l2Turns <= threshold) {
            metrics.incResult("below_threshold", "config");
            return new CompactResult(messages, false, "below_threshold:" + l2Turns);
        }

        // 3. split pre-recent / recent
        if (part.l2.size() <= recentK) {
            metrics.incResult("too_short", "config");
            return new CompactResult(messages, false, "l2_too_short_for_preRecent");
        }
        int splitIdx = part.l2.size() - recentK;
        List<Map<String, Object>> preRecent = new ArrayList<>(part.l2.subList(0, splitIdx));
        List<Map<String, Object>> recent = new ArrayList<>(part.l2.subList(splitIdx, part.l2.size()));
        metrics.recordPreRecentSize(preRecent.size());

        // 4. cache lookup（用 preRecent 在原 messages 数组中的位置做 key）
        Long fromIdx = messageArrayIndex(messages, preRecent.get(0));
        Long toIdx = messageArrayIndex(messages, preRecent.get(preRecent.size() - 1));
        Optional<ConversationSummaryRow> cached = summaryDao.findSummary(conversationId, fromIdx, toIdx);

        String summaryText;
        String source;

        try {
            if (cached.isPresent()) {
                summaryText = cached.get().getSummaryText();
                source = "cache";
                metrics.incResult("hit", "cache");
                log.info("[ConversationCompactService] Cache hit: convId={}, from={}, to={}, len={}",
                        conversationId, fromIdx, toIdx, summaryText != null ? summaryText.length() : 0);
            } else {
                LlmConfig cfg = resolveLlmConfig(userId);
                if (cfg == null) {
                    log.warn("[ConversationCompactService] No LLM config for userId={}, tier 3 fallback", userId);
                    metrics.incResult("fallback", "no_llm_config");
                    return finalize(assembleTier3(part, recent, "no_llm_config"), startedAtMs);
                }
                summaryText = llmHttpClient.chatCompletion(
                        cfg.apiBase, cfg.apiKey, cfg.model,
                        buildSummaryPrompt(preRecent));
                source = cfg.source;
                summaryDao.saveSummary(new ConversationSummaryRow(
                        conversationId, fromIdx, toIdx, summaryText, cfg.model, null, null));
                metrics.incResult("miss", cfg.source);
                log.info("[ConversationCompactService] LLM summary OK: convId={}, from={}, to={}, source={}, len={}",
                        conversationId, fromIdx, toIdx, source,
                        summaryText != null ? summaryText.length() : 0);
            }
        } catch (Exception e) {
            log.warn("[ConversationCompactService] LLM failed: convId={}, from={}, to={}, err={} — tier 3 fallback",
                    conversationId, fromIdx, toIdx, e.getMessage());
            metrics.incResult("fallback", "llm_failed");
            return finalize(assembleTier3(part, recent, "llm_failed:" + e.getClass().getSimpleName()), startedAtMs);
        }

        return finalize(assembleTier2(part, recent, summaryText, source), startedAtMs);
    }

    /** 在 compact 末尾打点 + 写结构化日志 */
    private CompactResult finalize(CompactResult r, long startedAtMs) {
        long latency = System.currentTimeMillis() - startedAtMs;
        metrics.recordLatencyMs(latency);
        log.info("[ConversationCompactService.compact] result={} source={} latencyMs={} messages_in={} messages_out={} {}",
                r.summaryApplied ? "applied" : "skipped",
                r.summarySource,
                latency,
                "-",  // 输入 messages 数需要从 caller 传，这里省略
                r.messages != null ? r.messages.size() : 0,
                metrics.snapshot().toStructuredLog());
        return r;
    }

    LayerPartition partition(List<Map<String, Object>> messages) {
        LayerPartition p = new LayerPartition();
        for (Map<String, Object> m : messages) {
            String role = asString(m.get("role"));
            String source = asString(m.get("source"));
            boolean hasToolCalls = m.get("tool_calls") instanceof List && !((List<?>) m.get("tool_calls")).isEmpty();

            if ("system".equalsIgnoreCase(role)) {
                p.l0.add(m);
            } else if ("tool".equalsIgnoreCase(role) || "tool_result".equalsIgnoreCase(role)
                    || "function".equalsIgnoreCase(role) || hasToolCalls
                    || "ASYNC_TASK_RESULT".equalsIgnoreCase(source)) {
                p.l1.add(m);
            } else if ("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)
                    || "human".equalsIgnoreCase(role) || "ai".equalsIgnoreCase(role)) {
                if ("human".equalsIgnoreCase(role)) m = withRole(m, "user");
                else if ("ai".equalsIgnoreCase(role)) m = withRole(m, "assistant");
                p.l2.add(m);
            } else {
                p.l1.add(m);
            }
        }
        return p;
    }

    int countL2Turns(List<Map<String, Object>> l2) {
        int n = 0;
        for (Map<String, Object> m : l2) {
            if ("user".equalsIgnoreCase(asString(m.get("role")))) {
                n++;
            }
        }
        return n;
    }

    private CompactResult assembleTier2(LayerPartition part, List<Map<String, Object>> recent,
                                        String summaryText, String source) {
        Map<String, Object> summaryBlock = new HashMap<>();
        summaryBlock.put("role", "system");
        summaryBlock.put("content", "以下是历史摘要：\n" + summaryText);
        summaryBlock.put("source", "compaction_summary");

        List<Map<String, Object>> compacted = new ArrayList<>();
        compacted.addAll(part.l0);
        compacted.addAll(part.l1);
        compacted.add(summaryBlock);
        compacted.addAll(recent);

        return new CompactResult(compacted, true, source);
    }

    private CompactResult assembleTier3(LayerPartition part, List<Map<String, Object>> recent,
                                        String reason) {
        List<Map<String, Object>> compacted = new ArrayList<>();
        compacted.addAll(part.l0);
        compacted.addAll(part.l1);
        compacted.addAll(recent);
        return new CompactResult(compacted, false, reason);
    }

    private Long messageArrayIndex(List<Map<String, Object>> messages, Map<String, Object> target) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) return (long) i;
        }
        return -1L;
    }

    private LlmConfig resolveLlmConfig(String userId) {
        if (userId != null) {
            try {
                User user = userMapper.selectById(userId);
                Map<String, String> cfg = userService.mergeLlmConfigForAgent(user);
                String apiBase = cfg.get("llmApiBase");
                String apiKey = cfg.get("llmApiKey");
                String model = cfg.get("llmModelName");
                if (apiKey != null && !apiKey.isEmpty() && apiBase != null && !apiBase.isEmpty()
                        && model != null && !model.isEmpty()) {
                    return new LlmConfig(apiBase, apiKey, model, "user");
                }
            } catch (Exception e) {
                log.warn("[ConversationCompactService] Failed to load user LLM config (userId={}): {}",
                        userId, e.getMessage());
            }
        }
        String apiBase = System.getenv("DEFAULT_LLM_API_BASE");
        String apiKey = System.getenv("DEFAULT_LLM_API_KEY");
        String model = System.getenv("DEFAULT_LLM_MODEL");
        if (apiKey == null || apiKey.isEmpty() || apiBase == null || apiBase.isEmpty()
                || model == null || model.isEmpty()) {
            return null;
        }
        return new LlmConfig(apiBase, apiKey, model, "system");
    }

    private List<Map<String, String>> buildSummaryPrompt(List<Map<String, Object>> preRecent) {
        List<Map<String, String>> result = new ArrayList<>();

        Map<String, String> system = new HashMap<>();
        system.put("role", "system");
        system.put("content", SUMMARY_PROMPT);
        result.add(system);

        StringBuilder userContent = new StringBuilder();
        userContent.append("## 待压缩的对话历史（").append(preRecent.size()).append(" 条）\n\n");
        for (Map<String, Object> m : preRecent) {
            String role = asString(m.get("role"));
            String content = asString(m.get("content"));
            if (content.length() > 800) {
                content = content.substring(0, 800) + "...";
            }
            userContent.append("[").append(role).append("] ").append(content).append("\n\n");
        }

        Map<String, String> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", userContent.toString());
        result.add(user);

        return result;
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Map<String, Object> withRole(Map<String, Object> orig, String newRole) {
        Map<String, Object> copy = new HashMap<>(orig);
        copy.put("role", newRole);
        return copy;
    }

    static class LayerPartition {
        final List<Map<String, Object>> l0 = new ArrayList<>();
        final List<Map<String, Object>> l1 = new ArrayList<>();
        final List<Map<String, Object>> l2 = new ArrayList<>();
    }

    private static class LlmConfig {
        final String apiBase;
        final String apiKey;
        final String model;
        final String source;

        LlmConfig(String apiBase, String apiKey, String model, String source) {
            this.apiBase = apiBase;
            this.apiKey = apiKey;
            this.model = model;
            this.source = source;
        }
    }

    public static class CompactResult {
        public final List<Map<String, Object>> messages;
        public final boolean summaryApplied;
        public final String summarySource;

        public CompactResult(List<Map<String, Object>> messages, boolean summaryApplied, String summarySource) {
            this.messages = messages;
            this.summaryApplied = summaryApplied;
            this.summarySource = summarySource;
        }
    }
}
