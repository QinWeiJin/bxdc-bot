package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    default List<AsyncTask> findPendingOrPolling(int limit) {
        // ★ 关键修复：SINGLE_CALLED 状态的任务**不能**被重新扫到。
        // 原因：SINGLE_CALL 任务一旦被某个线程 claim（status 从 PENDING → SINGLE_CALLED），
        //       该线程会同步阻塞等 upstream 返回（可能 30s ~ 10min）。
        //       如果 scheduler 仍把 SINGLE_CALLED 加入 pick list，30s 后会再次扫到它，
        //       派发第二个线程并发调 upstream → 用户看到同一个 GET 被调了 N 次。
        // 单次 in-flight 即可，线程结束时会置 COMPLETED/FAILED/TIMEOUT。
        // 卡死的 SINGLE_CALLED 任务由 StartupRecoveryRunner 兜底（启动时把超时未归位的标 FAILED）。
        return selectList(new LambdaQueryWrapper<AsyncTask>()
                .in(AsyncTask::getStatus, "PENDING", "POLLING")
                .and(w -> w.isNull(AsyncTask::getLastPolledAt)
                        .or()
                        // 用 NOW() 不用 UTC_TIMESTAMP()：连接时区是 Asia/Shanghai（JDBC URL serverTimezone=Asia/Shanghai），
                        // last_polled_at 也是 JVM 本地时间写入；用 UTC 会差 8 小时导致 diff 永远为负数，
                        // 任务只在首次扫描被扫到，之后永远不会被重新轮询。
                        .apply("TIMESTAMPDIFF(SECOND, last_polled_at, NOW()) >= poll_interval_seconds"))
                .orderByAsc(AsyncTask::getCreatedAt)
                .last("LIMIT " + limit));
    }

    default List<AsyncTask> findBySessionId(String sessionId) {
        return selectList(new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getSessionId, sessionId)
                .in(AsyncTask::getStatus, "PENDING", "POLLING")
                .orderByAsc(AsyncTask::getCreatedAt));
    }

    /**
     * 列出某用户的异步任务（普通 async + Bxdcbot 合成通知）。
     * 普通 async 任务要求 poll_endpoint IS NOT NULL。
     * Bxdcbot 合成通知（subtask_only=1）即使 poll_endpoint 为 NULL 也要返回，
     * 让用户能在通知中心看到 Bxdcbot 调用的所有子任务（包括 sync 子任务）。
     * 如果 unreadOnly=true，额外限制 notified_at IS NULL。
     * 如果 parentToolId 非空，额外限制 parent_tool_id = parentToolId
     * （bxdcbot-multi-turn-async change：前端按 Bxdcbot run 聚合子任务用）。
     */
    default List<AsyncTask> findByUserAndAsyncPoll(String userId, boolean unreadOnly, int limit, String parentToolId) {
        LambdaQueryWrapper<AsyncTask> w = new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getUserId, userId)
                .and(q -> q.isNotNull(AsyncTask::getPollEndpoint)
                        .or()
                        .eq(AsyncTask::getSubtaskOnly, 1))
                .orderByDesc(AsyncTask::getCreatedAt)
                .last("LIMIT " + limit);
        if (unreadOnly) {
            w.isNull(AsyncTask::getNotifiedAt);
        }
        if (parentToolId != null && !parentToolId.isEmpty()) {
            w.eq(AsyncTask::getParentToolId, parentToolId);
        }
        return selectList(w);
    }

    default int countUnreadByUser(String userId) {
        Long count = selectCount(new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getUserId, userId)
                .and(q -> q.isNotNull(AsyncTask::getPollEndpoint)
                        .or()
                        .eq(AsyncTask::getSubtaskOnly, 1))
                .in(AsyncTask::getStatus, "COMPLETED", "FAILED", "TIMEOUT")
                .isNull(AsyncTask::getNotifiedAt));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 标记单条任务为已读。仅允许标属于自己的任务。
     * 使用 @Update 注解方式（不通过 BaseMapper.update），避免类型推断问题。
     */
    @Update("UPDATE async_tasks SET notified_at = UTC_TIMESTAMP() " +
            "WHERE id = #{taskId} AND user_id = #{userId} AND notified_at IS NULL")
    int markRead(@org.apache.ibatis.annotations.Param("taskId") Long taskId,
                 @org.apache.ibatis.annotations.Param("userId") String userId);

    /**
     * 删除单条任务。仅允许删除属于自己的任务。
     * 1 表示删除成功，0 表示任务不存在或不属于该用户。
     */
    @Update("DELETE FROM async_tasks WHERE id = #{taskId} AND user_id = #{userId}")
    int deleteByIdAndUser(@org.apache.ibatis.annotations.Param("taskId") Long taskId,
                          @org.apache.ibatis.annotations.Param("userId") String userId);

    /**
     * 批量删除任务。仅删除属于该用户的任务。
     * 返回实际删除的行数（可能小于请求数量）。
     */
    @Update({
        "<script>",
        "DELETE FROM async_tasks WHERE user_id = #{userId} AND id IN ",
        "<foreach item='id' collection='ids' open='(' separator=',' close=')'>",
        "#{id}",
        "</foreach>",
        "</script>"
    })
    int deleteByIdsAndUser(@org.apache.ibatis.annotations.Param("userId") String userId,
                           @org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);

    /**
     * 自动清理：把"超过 7 天的已完成/失败/超时且未读"任务批量标为已读。
     * 避免历史数据堆积推给用户。
     */
    @Update("UPDATE async_tasks SET notified_at = UTC_TIMESTAMP() " +
            "WHERE notified_at IS NULL " +
            "AND status IN ('COMPLETED','FAILED','TIMEOUT') " +
            "AND completed_at IS NOT NULL " +
            "AND completed_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 7 DAY)")
    int autoMarkStaleAsRead();

    /**
     * 启动恢复：把"卡住超过 N 分钟"的 SINGLE_CALLED 任务标为 FAILED。
     * SINGLE_CALL 任务没有 PENDING→SINGLE_CALLED 之外的轮询推进，如果 JVM 崩溃后恢复，
     * 这些任务会永远卡在 SINGLE_CALLED 状态。
     *
     * @param minutes 卡住分钟数阈值（调用方传常量 30）
     * @return 受影响的行数
     */
    @Update("UPDATE async_tasks " +
            "SET status = 'FAILED', " +
            "    error_message = CONCAT('Marked FAILED by startup recovery: stuck in SINGLE_CALLED for more than ', #{minutes}, ' minutes'), " +
            "    completed_at = NOW(), " +
            "    updated_at = NOW() " +
            "WHERE status = 'SINGLE_CALLED' " +
            "AND updated_at < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int recoverStuckSingleCallTasks(@org.apache.ibatis.annotations.Param("minutes") int minutes);

    /**
     * 按 session 维度查找最近的请求签名匹配任务（去重核心查询）。
     * - 优先按 (userId, sessionId, signature) 查（per-session 1 小时窗口）
     * - 如果 sessionId 为空，按 (userId, signature) 查（fallback 60s 窗口）
     * - 任意状态（PENDING/POLLING/SINGLE_CALLED/COMPLETED/FAILED/TIMEOUT）都算重复
     *   ——同一对话里"调完就有结果"的情况下，agent 也不能再调第二次
     */
    default AsyncTask findRecentBySignatureInSession(
            String userId, String sessionId, String signature, int windowSeconds) {
        LambdaQueryWrapper<AsyncTask> wrapper = new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getUserId, userId)
                .eq(AsyncTask::getRequestSignature, signature)
                .ge(AsyncTask::getCreatedAt,
                    LocalDateTime.now().minusSeconds(windowSeconds))
                .orderByDesc(AsyncTask::getCreatedAt)
                .last("LIMIT 1");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            wrapper.eq(AsyncTask::getSessionId, sessionId);
        }
        return selectOne(wrapper);
    }
}
