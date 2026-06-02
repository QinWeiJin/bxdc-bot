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
        return selectList(new LambdaQueryWrapper<AsyncTask>()
                .in(AsyncTask::getStatus, "PENDING", "POLLING")
                .and(w -> w.isNull(AsyncTask::getLastPolledAt)
                        .or()
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
     * 列出某用户的异步任务（仅返回走 asyncPoll 分支创建的任务）。
     * 通过 poll_endpoint IS NOT NULL 过滤掉非异步调用。
     * 如果 unreadOnly=true，额外限制 notified_at IS NULL。
     */
    default List<AsyncTask> findByUserAndAsyncPoll(String userId, boolean unreadOnly, int limit) {
        LambdaQueryWrapper<AsyncTask> w = new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getUserId, userId)
                .isNotNull(AsyncTask::getPollEndpoint)
                .orderByDesc(AsyncTask::getCreatedAt)
                .last("LIMIT " + limit);
        if (unreadOnly) {
            w.isNull(AsyncTask::getNotifiedAt);
        }
        return selectList(w);
    }

    default int countUnreadByUser(String userId) {
        Long count = selectCount(new LambdaQueryWrapper<AsyncTask>()
                .eq(AsyncTask::getUserId, userId)
                .isNotNull(AsyncTask::getPollEndpoint)
                .in(AsyncTask::getStatus, "COMPLETED", "FAILED", "TIMEOUT")
                .isNull(AsyncTask::getNotifiedAt));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 标记单条任务为已读。仅允许标属于自己的任务。
     * 使用 @Update 注解方式（不通过 BaseMapper.update），避免类型推断问题。
     */
    @Update("UPDATE async_tasks SET notified_at = NOW() " +
            "WHERE id = #{taskId} AND user_id = #{userId} AND notified_at IS NULL")
    int markRead(@org.apache.ibatis.annotations.Param("taskId") Long taskId,
                 @org.apache.ibatis.annotations.Param("userId") String userId);

    /**
     * 自动清理：把"超过 7 天的已完成/失败/超时且未读"任务批量标为已读。
     * 避免历史数据堆积推给用户。
     */
    @Update("UPDATE async_tasks SET notified_at = NOW() " +
            "WHERE notified_at IS NULL " +
            "AND status IN ('COMPLETED','FAILED','TIMEOUT') " +
            "AND completed_at IS NOT NULL " +
            "AND completed_at < DATE_SUB(NOW(), INTERVAL 7 DAY)")
    int autoMarkStaleAsRead();
}
