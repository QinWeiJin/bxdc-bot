package com.lobsterai.skillgateway.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * 约束（AGENTS.md 5.1）：尽量不新增第三方包。用 JDK 1.8 原生 HttpURLConnection。
 */
@Component
public class LlmHttpClient {

    private static final Logger log = LoggerFactory.getLogger(LlmHttpClient.class);

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 60000;

    private final ObjectMapper objectMapper;

    @Autowired
    public LlmHttpClient(ObjectMapper objectMapper) {
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
        String urlStr = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        urlStr = urlStr + "/chat/completions";

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

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            byte[] bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            String responseBody = readResponse(conn, status);

            if (status / 100 != 2) {
                throw new LlmHttpException("LLM returned HTTP " + status + ": " + truncate(responseBody, 500));
            }

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
            throw new LlmHttpException("HTTP request failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readResponse(HttpURLConnection conn, int status) throws IOException {
        BufferedReader reader;
        if (status / 100 == 2) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
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
