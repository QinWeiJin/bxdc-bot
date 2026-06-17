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
 * 用户级别 SSE 事件总线。
 *
 * 与 {@link ConversationEventBus} 的区别：
 * - ConversationEventBus 按 conversationId 路由（同一对话多 tab 同步）
 * - UserEventBus 按 userId 路由（用户全局事件，跨 conversation，比如通知中心未读数变化）
 *
 * 用途：
 * - 异步任务状态变化 → unread 计数变化 → 推送到 user 级别 SSE
 * - 用户 markRead / delete task → 同样推
 *
 * 限制：单实例 in-memory；多实例部署需要切到 Redis Pub/Sub。
 */
@Component
public class UserEventBus {

    private static final Logger log = LoggerFactory.getLogger(UserEventBus.class);

    private final Map<String, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.info("[UserEventBus] register userId={} (current subscribers: {})",
                userId, emittersByUser.get(userId).size());

        Runnable cleanup = () -> removeEmitter(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().comment("subscribed"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    /**
     * 推事件到该 user 的所有订阅者。失败自动清理断开的 emitter。
     */
    public void publish(String userId, String eventName, Object data) {
        if (userId == null || userId.isEmpty()) return;
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("[UserEventBus] publish skipped (no subscribers) userId={} event={}", userId, eventName);
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                log.debug("[UserEventBus] send failed, removing emitter: {}", e.getMessage());
                removeEmitter(userId, emitter);
            }
        }
    }

    public int subscriberCount(String userId) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        return emitters == null ? 0 : emitters.size();
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByUser.remove(userId);
            }
        }
    }
}
