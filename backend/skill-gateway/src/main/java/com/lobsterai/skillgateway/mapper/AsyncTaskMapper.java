package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;

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
}
