package com.lobsterai.skillgateway.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM HTTP 客户端（OpenAI-compatible chat/completions）
 *
 * open spec: async-task-result-echo-to-chat
 *
 * 用法：gateway 内部 AsyncTaskChatReplyService 调用，调一次 LLM 拿回纯文本总结。
 * 不做流式（fire-and-forget 场景，agent-core 也不需要流）。
 *
 * 约束（AGENTS.md 5.1）：尽量不新增第三方包。用 JDK 11+ HttpClient（Spring Boot 2.7 自带）。
 */
@Component
public class LlmHttpClient {

    private static final Logger log = LoggerFactory.getLogger(LlmHttpClient.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public LlmHttpClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 调一次 OpenAI-compatible chat/completions（非流式）。
     *
     * @param apiBase  e.g. "https://api.openai.com/v1"
     * @param apiKey   Bearer token
     * @param model    模型名
     * @param messages system + user（顺序敏感）
     * @return LLM 输出的第一条 choice.message.content；失败抛 LlmHttpException
     */
    public String chatCompletion(String apiBase, String apiKey, String model, List<Map<String, String>> messages) {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new LlmHttpException("apiBase is empty");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new LlmHttpException("apiKey is empty");
        }
        if (model == null || model.isEmpty()) {
            throw new LlmHttpException("model is empty");
        }

        // 构造 URL
        String url = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        url = url + "/chat/completions";

        // 构造 request body
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);

        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmHttpException("Failed to serialize request body: " + e.getMessage());
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new LlmHttpException("Failed to build request: " + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new LlmHttpException("HTTP send failed: " + e.getMessage());
        }

        int status = response.statusCode();
        String responseBody = response.body();

        if (status / 100 != 2) {
            throw new LlmHttpException("LLM returned HTTP " + status + ": " + truncate(responseBody, 500));
        }

        try {
            ChatCompletionResponse parsed = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            if (parsed.choices == null || parsed.choices.isEmpty()) {
                throw new LlmHttpException("LLM returned no choices");
            }
            ChatChoice first = parsed.choices.get(0);
            if (first.message == null || first.message.content == null) {
                throw new LlmHttpException("LLM returned empty content");
            }
            return first.message.content;
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmHttpException("Failed to parse LLM response: " + e.getMessage() + " | body: " + truncate(responseBody, 500));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    public static class LlmHttpException extends RuntimeException {
        public LlmHttpException(String message) {
            super(message);
        }
    }

    // ---- response DTOs ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatCompletionResponse {
        @JsonProperty("choices")
        public List<ChatChoice> choices;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatChoice {
        @JsonProperty("index")
        public Integer index;

        @JsonProperty("message")
        public ChatMessage message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatMessage {
        @JsonProperty("role")
        public String role;

        @JsonProperty("content")
        public String content;
    }
}
