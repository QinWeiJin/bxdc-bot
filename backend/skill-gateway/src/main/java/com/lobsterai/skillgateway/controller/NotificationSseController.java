package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.event.UserEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 用户级别 SSE 端点 —— 用于通知中心实时更新。
 *
 * 事件类型：
 * - "unread_count_changed" payload={count: N} 异步任务 unread 计数变化时触发
 *
 * EventSource 浏览器 API 不支持自定义 header，所以 userId 通过 ?userId= query 传入。
 */
@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*")
public class NotificationSseController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseController.class);

    private final UserEventBus eventBus;

    public NotificationSseController(UserEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotificationEvents(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(value = "userId", required = false) String userIdParam) {
        String userId = userIdHeader != null ? userIdHeader : userIdParam;
        if (userId == null || userId.isEmpty()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing user identity");
        }
        log.info("[NotificationSseController] SSE register userId={}", userId);
        return eventBus.register(userId);
    }
}
