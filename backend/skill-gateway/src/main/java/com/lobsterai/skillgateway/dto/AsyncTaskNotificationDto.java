package com.lobsterai.skillgateway.dto;

import com.lobsterai.skillgateway.entity.AsyncTask;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 异步任务通知 DTO。
 * 用于通知中心列表展示，合并了 AsyncTask + skill.name + 进度统计。
 *
 * 时间字段一律按 Asia/Shanghai 序列化为 ISO 8601 字符串
 * （"yyyy-MM-dd'T'HH:mm:ssXXX"，例：2024-01-15T10:30:00+08:00），
 * 由前端 utils/datetime.ts 的 parseBackendTimeAsUtc 反序列化为本地时间显示。
 *
 * 注：字段类型故意用 String 而不是 LocalDateTime，
 * 因为 Jackson 2.13.3 + JavaTimeModule + @JsonFormat(pattern="...XXX")
 * 在 LocalDateTime 上有兼容性问题（会触发 "Unsupported field: OffsetSeconds"），
 * 改用 String 后序列化稳定。
 */
public class AsyncTaskNotificationDto {

    private static final DateTimeFormatter SHANGHAI_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                    .withZone(ZoneId.of("Asia/Shanghai"));

    private static String formatShanghai(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.atZone(ZoneId.of("Asia/Shanghai")).format(SHANGHAI_FMT);
    }

    private Long id;
    private Long skillId;
    private String skillName;
    private String externalTaskId;
    private String sessionId;

    private String status;
    private String pollStrategy; // 'PERIODIC' | 'SINGLE_CALL' | null
    private Integer retryCount;
    private Long elapsedSeconds;
    private Integer pollResponseCount;

    private String errorMessage;

    private String startedAt;
    private String completedAt;
    private String createdAt;
    private String notifiedAt;

    private boolean unread;
    private String previewResult;

    /** 异步任务最大等待秒数（PERIODIC 的 maxWaitSeconds / SINGLE_CALL 的 singleCallReadTimeoutSeconds）。前端进度条用。 */
    private Integer maxWaitSeconds;

    /** bxdcbot-multi-turn-async：父 Bxdcbot run_id（NULL=普通 async） */
    private String parentToolId;
    /** bxdcbot-multi-turn-async：父 Bxdcbot skill_id（NULL=非 Bxdcbot 调起） */
    private Long parentSkillId;

    /** bxdcbot-multi-turn-async：父 Bxdcbot skill 名称（前端通知中心用） */
    private String parentSkillName;

    public static AsyncTaskNotificationDto from(AsyncTask t, String skillName, int pollResponseCount,
                                                Long elapsedSeconds, String previewResult,
                                                Integer maxWaitSeconds) {
        AsyncTaskNotificationDto d = new AsyncTaskNotificationDto();
        d.id = t.getId();
        d.skillId = t.getSkillId();
        d.skillName = skillName;
        d.externalTaskId = t.getExternalTaskId();
        d.sessionId = t.getSessionId();
        d.status = t.getStatus();
        d.pollStrategy = t.getPollStrategy();
        d.retryCount = t.getPollRetryCount() == null ? 0 : t.getPollRetryCount();
        d.elapsedSeconds = elapsedSeconds;
        d.pollResponseCount = pollResponseCount;
        d.errorMessage = t.getErrorMessage();
        d.startedAt = formatShanghai(t.getStartedAt());
        d.completedAt = formatShanghai(t.getCompletedAt());
        d.createdAt = formatShanghai(t.getCreatedAt());
        d.notifiedAt = formatShanghai(t.getNotifiedAt());
        d.unread = t.getNotifiedAt() == null
                && Arrays.asList("COMPLETED", "FAILED", "TIMEOUT").contains(t.getStatus());
        d.previewResult = previewResult;
        d.maxWaitSeconds = maxWaitSeconds;
        d.parentToolId = t.getParentToolId();
        d.parentSkillId = t.getParentSkillId();
        return d;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getExternalTaskId() { return externalTaskId; }
    public void setExternalTaskId(String externalTaskId) { this.externalTaskId = externalTaskId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPollStrategy() { return pollStrategy; }
    public void setPollStrategy(String pollStrategy) { this.pollStrategy = pollStrategy; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Long getElapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(Long elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }
    public Integer getPollResponseCount() { return pollResponseCount; }
    public void setPollResponseCount(Integer pollResponseCount) { this.pollResponseCount = pollResponseCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(String notifiedAt) { this.notifiedAt = notifiedAt; }
    public boolean isUnread() { return unread; }
    public void setUnread(boolean unread) { this.unread = unread; }
    public String getPreviewResult() { return previewResult; }
    public void setPreviewResult(String previewResult) { this.previewResult = previewResult; }

    public Integer getMaxWaitSeconds() { return maxWaitSeconds; }
    public void setMaxWaitSeconds(Integer maxWaitSeconds) { this.maxWaitSeconds = maxWaitSeconds; }

    public String getParentToolId() { return parentToolId; }
    public void setParentToolId(String parentToolId) { this.parentToolId = parentToolId; }
    public Long getParentSkillId() { return parentSkillId; }
    public void setParentSkillId(Long parentSkillId) { this.parentSkillId = parentSkillId; }
    public String getParentSkillName() { return parentSkillName; }
    public void setParentSkillName(String parentSkillName) { this.parentSkillName = parentSkillName; }
}
