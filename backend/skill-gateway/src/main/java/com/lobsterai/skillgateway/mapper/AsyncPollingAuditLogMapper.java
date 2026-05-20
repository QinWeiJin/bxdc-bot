package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.AsyncPollingAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AsyncPollingAuditLogMapper extends BaseMapper<AsyncPollingAuditLog> {
}
