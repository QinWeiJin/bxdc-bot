package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.entity.User;
import com.lobsterai.skillgateway.http.LlmHttpClient;
import com.lobsterai.skillgateway.http.LlmHttpClient.LlmHttpException;
import com.lobsterai.skillgateway.mapper.SkillMapper;
import com.lobsterai.skillgateway.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AsyncTaskChatReplyService 单元测试（open spec: async-task-result-echo-to-chat）
 *
 * 覆盖各路径：
 * 1. null task → 直接 return
 * 2. 写消息失败（insertAsyncTaskResult 返回 null）→ 跳过 LLM
 * 3. 用户未配 apiKey → 降级 UPDATE（fallback 文本）
 * 4. LLM 调成功 → UPDATE summary（success=true）+ 写 audit
 * 5. LLM 调失败 → UPDATE summary（fallback, success=false）+ 写 audit 失败记录
 * 6. taskResult 超长 → 截断（验证 prompt 里 user content 是截断后的）
 * 7. skill 没找到 → 用默认 toolName
 * 8. audit save 抛异常 → 主流程不挂
 *
 * 纯 Mockito 单元测试（无 Spring context），避免 @Async 代理干扰。
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskChatReplyServiceTest {

    @Mock private ChatMessageService chatMessageService;
    @Mock private UserMapper userMapper;
    @Mock private SkillMapper skillMapper;
    @Mock private UserService userService;
    @Mock private LlmHttpClient llmHttpClient;
    @Mock private LlmHttpAuditService llmHttpAuditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AsyncTaskChatReplyService service;

    @BeforeEach
    void setUp() {
        service = new AsyncTaskChatReplyService(
                chatMessageService,
                userMapper,
                skillMapper,
                userService,
                llmHttpClient,
                llmHttpAuditService,
                objectMapper
        );
    }

    /** 构造一个最小可用的 AsyncTask 用于测试。 */
    private AsyncTask sampleTask() {
        AsyncTask t = new AsyncTask();
        t.setId(42L);
        t.setUserId("u-1");
        t.setSessionId("conv-1");
        t.setSkillId(7L);
        t.setExternalTaskId("ext-99");
        t.setStatus("SUCCESS");
        t.setPollResult("{\"foo\":\"bar\"}");
        t.setRequestBody("{\"q\":\"what time is it\"}");
        t.setCompletedAt(LocalDateTime.of(2026, 6, 12, 10, 30, 0));
        return t;
    }

    private Map<String, String> llmConfig(String apiKey) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("llmApiBase", "https://api.openai.com/v1");
        cfg.put("llmApiKey", apiKey);
        cfg.put("llmModelName", "gpt-4o-mini");
        return cfg;
    }

    @Test
    void onTaskTerminal_nullTask_returnsSilently() {
        service.onTaskTerminal(null);

        verifyNoInteractions(chatMessageService, userMapper, llmHttpClient);
    }

    @Test
    void onTaskTerminal_insertReturnsNull_skipsLlmReply() {
        AsyncTask task = sampleTask();
        when(chatMessageService.insertAsyncTaskResult(eq("conv-1"), eq(task), anyString())).thenReturn(null);

        service.onTaskTerminal(task);

        verify(chatMessageService, times(1)).insertAsyncTaskResult(eq("conv-1"), eq(task), anyString());
        verify(chatMessageService, never()).updateLlmSummary(anyLong(), anyString(), any(Boolean.class));
        verifyNoInteractions(llmHttpClient, llmHttpAuditService, userMapper);
    }

    @Test
    void onTaskTerminal_noApiKey_writesFallbackSummary() {
        AsyncTask task = sampleTask();
        User user = new User();
        user.setId("u-1");
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(100L);
        when(userMapper.selectById("u-1")).thenReturn(user);
        when(userService.mergeLlmConfigForAgent(user)).thenReturn(llmConfig(null)); // no apiKey

        service.onTaskTerminal(task);

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatMessageService, times(1)).updateLlmSummary(eq(100L), summaryCaptor.capture(), eq(false));
        assertEquals(ChatMessageService.FALLBACK_SUMMARY_TEXT, summaryCaptor.getValue());

        // 没有 apiKey 不应调 LLM、不写 audit
        verifyNoInteractions(llmHttpClient, llmHttpAuditService);
    }

    @Test
    void onTaskTerminal_llmSuccess_writesSummaryAndAudit() {
        AsyncTask task = sampleTask();
        User user = new User();
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(101L);
        when(userMapper.selectById("u-1")).thenReturn(user);
        when(userService.mergeLlmConfigForAgent(user)).thenReturn(llmConfig("sk-test"));
        when(llmHttpClient.chatCompletion(anyString(), anyString(), anyString(), any())).thenReturn("LLM 总结：任务完成，结果是 bar");

        service.onTaskTerminal(task);

        // 写 summary（success=true）
        verify(chatMessageService, times(1)).updateLlmSummary(eq(101L), eq("LLM 总结：任务完成，结果是 bar"), eq(true));

        // 写 audit（success=true）
        ArgumentCaptor<JsonNode> auditCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(llmHttpAuditService, times(1)).saveEvent(auditCaptor.capture());
        JsonNode audit = auditCaptor.getValue();
        assertEquals("ASYNC_TASK_REPLY", audit.get("auditTag").asText());
        assertTrue(audit.get("success").asBoolean());
        assertEquals("u-1", audit.get("userId").asText());
        assertEquals("gpt-4o-mini", audit.get("model").asText());
    }

    @Test
    void onTaskTerminal_llmFailure_writesFallbackAndFailedAudit() {
        AsyncTask task = sampleTask();
        User user = new User();
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(102L);
        when(userMapper.selectById("u-1")).thenReturn(user);
        when(userService.mergeLlmConfigForAgent(user)).thenReturn(llmConfig("sk-test"));
        when(llmHttpClient.chatCompletion(anyString(), anyString(), anyString(), any()))
                .thenThrow(new LlmHttpException("HTTP 500"));

        service.onTaskTerminal(task);

        // 写 fallback summary（success=false）
        verify(chatMessageService, times(1)).updateLlmSummary(eq(102L), eq(ChatMessageService.FALLBACK_SUMMARY_TEXT), eq(false));

        // 写 audit（success=false + errorMessage）
        ArgumentCaptor<JsonNode> auditCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(llmHttpAuditService, times(1)).saveEvent(auditCaptor.capture());
        JsonNode audit = auditCaptor.getValue();
        assertEquals("ASYNC_TASK_REPLY", audit.get("auditTag").asText());
        assertTrue(!audit.get("success").asBoolean());
        assertNotNull(audit.get("errorMessage"));
        assertTrue(audit.get("errorMessage").asText().contains("HTTP 500"));
    }

    @Test
    void onTaskTerminal_pollResultTooLong_truncatedInPrompt() {
        // 构造一个超长 pollResult（> 60000 chars 触发 truncateForLlm）
        StringBuilder big = new StringBuilder();
        int target = 70000;
        for (int i = 0; i < target; i++) {
            big.append('x');
        }
        AsyncTask task = sampleTask();
        task.setPollResult(big.toString());

        User user = new User();
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(103L);
        when(userMapper.selectById("u-1")).thenReturn(user);
        when(userService.mergeLlmConfigForAgent(user)).thenReturn(llmConfig("sk-test"));

        // 捕获 prompt 的 messages
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> msgCaptor =
                (ArgumentCaptor<List<Map<String, String>>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        when(llmHttpClient.chatCompletion(anyString(), anyString(), anyString(), msgCaptor.capture()))
                .thenReturn("short reply");

        service.onTaskTerminal(task);

        List<Map<String, String>> messages = msgCaptor.getValue();
        // messages 应该至少 2 条：system + user
        assertTrue(messages.size() >= 2);
        String userContent = messages.get(messages.size() - 1).get("content");
        // user content 应该远小于 70000（被截断）
        assertTrue(userContent.length() < target, "user prompt 应该被截断");
        assertTrue(userContent.contains("[content truncated due to size"),
                "user prompt 应包含截断标记，实际: " + userContent.substring(0, Math.min(100, userContent.length())));
    }

    @Test
    void onTaskTerminal_skillNotFound_usesDefaultToolName() {
        AsyncTask task = sampleTask();
        task.setSkillId(999L);
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(104L);
        when(skillMapper.selectById(999L)).thenReturn(null);

        service.onTaskTerminal(task);

        // 不应抛异常；toolName 用 "skill_999" 兜底
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatMessageService, times(1)).insertAsyncTaskResult(eq("conv-1"), eq(task), contentCaptor.capture());
        assertTrue(contentCaptor.getValue().contains("skill_999"), "content 应含兜底 toolName");
    }

    @Test
    void onTaskTerminal_auditSaveThrows_doesNotBreakMainFlow() {
        AsyncTask task = sampleTask();
        User user = new User();
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(105L);
        when(userMapper.selectById("u-1")).thenReturn(user);
        when(userService.mergeLlmConfigForAgent(user)).thenReturn(llmConfig("sk-test"));
        when(llmHttpClient.chatCompletion(anyString(), anyString(), anyString(), any())).thenReturn("LLM reply");
        // audit 抛异常
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(llmHttpAuditService).saveEvent(any(JsonNode.class));

        // 不应抛
        service.onTaskTerminal(task);

        // 主流程仍然 UPDATE summary
        verify(chatMessageService, times(1)).updateLlmSummary(eq(105L), eq("LLM reply"), eq(true));
    }

    @Test
    void onTaskTerminal_exceptionInAnyStep_doesNotPropagate() {
        AsyncTask task = sampleTask();
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString()))
                .thenThrow(new RuntimeException("DB down"));

        // 即使内部任何步骤抛，外层 onTaskTerminal 也应 swallow（fire-and-forget）
        service.onTaskTerminal(task);
        // 不抛即过
    }

    @Test
    void onTaskTerminal_userIdNull_skipsUserLookup() {
        AsyncTask task = sampleTask();
        task.setUserId(null);
        when(chatMessageService.insertAsyncTaskResult(anyString(), any(AsyncTask.class), anyString())).thenReturn(106L);
        // mergeLlmConfigForAgent(null) 应被调用（user=null 走系统默认 fallback）
        when(userService.mergeLlmConfigForAgent(null)).thenReturn(llmConfig(null)); // no apiKey

        service.onTaskTerminal(task);

        verify(userMapper, never()).selectById(anyString());
        verify(chatMessageService, times(1)).updateLlmSummary(eq(106L), eq(ChatMessageService.FALLBACK_SUMMARY_TEXT), eq(false));
    }
}