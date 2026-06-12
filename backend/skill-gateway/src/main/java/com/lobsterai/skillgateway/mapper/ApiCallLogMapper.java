package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lobsterai.skillgateway.entity.ApiCallLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {

    default Page<ApiCallLog> selectByConversationIdPaged(String conversationId, int page, int size) {
        Page<ApiCallLog> p = new Page<>(page, size);
        LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<ApiCallLog>()
                .eq(ApiCallLog::getConversationId, conversationId)
                .orderByDesc(ApiCallLog::getCreatedAt);
        return selectPage(p, wrapper);
    }
}
