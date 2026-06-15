package com.lobsterai.skillgateway.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 对话级别 SSE 事件总线。
 * <p>
 * 同一对话可能有多个订阅者（多 tab / 多设备 / 多个 SSE 客户端），
 * 写入/更新事件会向所有订阅者推送。
 * <p>
 * open spec: async-task-result-echo-to-chat —— 异步任务完成时把"消息插入/更新"事件
 * 推给所有正在浏览该对话的客户端，实现"自动弹出"实时刷新。
 * <p>
 * 注意：当前实现是单实例 in-memory；多实例部署时需要切到 Redis Pub/Sub
 * 或类似的跨实例消息中间件。MVP 阶段只考虑单机。
 */
@Component
public class ConversationEventBus {

    private static final Logger log = LoggerFactory.getLogger(ConversationEventBus.class);

    private final Map<String, List<SseEmitter>> emittersByConversation = new ConcurrentHashMap<>();

    public SseEmitter register(String conversationId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout（gateway 端不主动超时）
        emittersByConversation.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>())
                .add(emitter);
        log.info("[ConversationEventBus] register conversationId={} (current subscribers: {})",
                conversationId, emittersByConversation.get(conversationId).size());

        Runnable cleanup = () -> removeEmitter(conversationId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 立即发一个 comment 触发 flush（前端 EventSource.onopen 也会触发）
        try {
            emitter.send(SseEmitter.event().comment("subscribed"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    public void publish(String conversationId, String eventName, Object data) {
        List<SseEmitter> emitters = emittersByConversation.get(conversationId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("[ConversationEventBus] publish skipped (no subscribers) conversationId={} event={}",
                    conversationId, eventName);
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                log.debug("[ConversationEventBus] send failed, removing emitter: {}", e.getMessage());
                removeEmitter(conversationId, emitter);
            }
        }
    }

    public int subscriberCount(String conversationId) {
        List<SseEmitter> emitters = emittersByConversation.get(conversationId);
        return emitters == null ? 0 : emitters.size();
    }

    private void removeEmitter(String conversationId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByConversation.get(conversationId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByConversation.remove(conversationId);
            }
        }
    }
}
