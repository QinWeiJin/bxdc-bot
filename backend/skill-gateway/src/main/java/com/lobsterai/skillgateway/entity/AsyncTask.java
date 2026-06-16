package com.lobsterai.skillgateway.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("async_tasks")
public class AsyncTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("skill_id")
    private Long skillId;

    @TableField("user_id")
    private String userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("external_task_id")
    private String externalTaskId;

    @TableField("poll_endpoint")
    private String pollEndpoint;

    @TableField("poll_method")
    private String pollMethod;

    @TableField("poll_interval_seconds")
    private Integer pollIntervalSeconds;

    @TableField("max_wait_seconds")
    private Integer maxWaitSeconds;

    @TableField("completion_json_path")
    private String completionJsonPath;

    @TableField("completion_value")
    private String completionValue;

    @TableField("failed_values")
    private String failedValues;

    @TableField("result_json_path")
    private String resultJsonPath;

    @TableField("poll_headers")
    private String pollHeaders;

    @TableField("initial_response")
    private String initialResponse;

    @TableField("poll_result")
    private String pollResult;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("poll_retry_count")
    private Integer pollRetryCount;

    @TableField("last_polled_at")
    private LocalDateTime lastPolledAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("notified_at")
    private LocalDateTime notifiedAt;

    /** 'PERIODIC' = 周期轮询；'SINGLE_CALL' = 单次长调用（无 pollEndpoint，靠 HTTP 长 readTimeout 等结果）。 */
    @TableField("poll_strategy")
    private String pollStrategy;

    /** SINGLE_CALL 模式专用 read timeout（秒）；NULL 时回退到 maxWaitSeconds。 */
    @TableField("single_call_read_timeout_seconds")
    private Integer singleCallReadTimeoutSeconds;

    /** 请求签名 SHA-256 hex（去重用）。 */
    @TableField("request_signature")
    private String requestSignature;

    /**
     * SINGLE_CALL 模式专用：原始请求体（JSON 字符串）。
     * Scheduler 跑长调用时需要这份 body 去调第三方。
     * PERIODIC 模式为 NULL（PERIODIC 模式下，初始响应 body 存 initial_response）。
     */
    @TableField("request_body")
    private String requestBody;

    /** bxdcbot-multi-turn-async change：父 Bxdcbot run_id（标识这是 Bxdcbot X 调的第 N 个子任务）；NULL=普通 async */
    @TableField("parent_tool_id")
    private String parentToolId;

    /** bxdcbot-multi-turn-async change：父 Bxdcbot skill_id（冗余字段，方便按 skill 过滤） */
    @TableField("parent_skill_id")
    private Long parentSkillId;

    /**
     * 合成通知标记：1=这是 Bxdcbot 子任务的合成通知（sync 子任务也会被插入此表以显示在通知中心）；
     * 0=普通 async 任务（默认）。
     * subtask_only=1 时 poll_endpoint 必为 NULL，status 必为 COMPLETED。
     */
    @TableField("subtask_only")
    private Integer subtaskOnly;

    public AsyncTask() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getExternalTaskId() { return externalTaskId; }
    public void setExternalTaskId(String externalTaskId) { this.externalTaskId = externalTaskId; }

    public String getPollEndpoint() { return pollEndpoint; }
    public void setPollEndpoint(String pollEndpoint) { this.pollEndpoint = pollEndpoint; }

    public String getPollMethod() { return pollMethod; }
    public void setPollMethod(String pollMethod) { this.pollMethod = pollMethod; }

    public Integer getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(Integer pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

    public Integer getMaxWaitSeconds() { return maxWaitSeconds; }
    public void setMaxWaitSeconds(Integer maxWaitSeconds) { this.maxWaitSeconds = maxWaitSeconds; }

    public String getCompletionJsonPath() { return completionJsonPath; }
    public void setCompletionJsonPath(String completionJsonPath) { this.completionJsonPath = completionJsonPath; }

    public String getCompletionValue() { return completionValue; }
    public void setCompletionValue(String completionValue) { this.completionValue = completionValue; }

    public String getFailedValues() { return failedValues; }
    public void setFailedValues(String failedValues) { this.failedValues = failedValues; }

    public String getResultJsonPath() { return resultJsonPath; }
    public void setResultJsonPath(String resultJsonPath) { this.resultJsonPath = resultJsonPath; }

    public String getPollHeaders() { return pollHeaders; }
    public void setPollHeaders(String pollHeaders) { this.pollHeaders = pollHeaders; }

    public String getInitialResponse() { return initialResponse; }
    public void setInitialResponse(String initialResponse) { this.initialResponse = initialResponse; }

    public String getPollResult() { return pollResult; }
    public void setPollResult(String pollResult) { this.pollResult = pollResult; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getPollRetryCount() { return pollRetryCount; }
    public void setPollRetryCount(Integer pollRetryCount) { this.pollRetryCount = pollRetryCount; }

    public LocalDateTime getLastPolledAt() { return lastPolledAt; }
    public void setLastPolledAt(LocalDateTime lastPolledAt) { this.lastPolledAt = lastPolledAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }

    public String getPollStrategy() { return pollStrategy; }
    public void setPollStrategy(String pollStrategy) { this.pollStrategy = pollStrategy; }

    public Integer getSingleCallReadTimeoutSeconds() { return singleCallReadTimeoutSeconds; }
    public void setSingleCallReadTimeoutSeconds(Integer singleCallReadTimeoutSeconds) { this.singleCallReadTimeoutSeconds = singleCallReadTimeoutSeconds; }

    public String getRequestSignature() { return requestSignature; }
    public void setRequestSignature(String requestSignature) { this.requestSignature = requestSignature; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public String getParentToolId() { return parentToolId; }
    public void setParentToolId(String parentToolId) { this.parentToolId = parentToolId; }

    public Long getParentSkillId() { return parentSkillId; }
    public void setParentSkillId(Long parentSkillId) { this.parentSkillId = parentSkillId; }

    public Integer getSubtaskOnly() { return subtaskOnly; }
    public void setSubtaskOnly(Integer subtaskOnly) { this.subtaskOnly = subtaskOnly; }
}
