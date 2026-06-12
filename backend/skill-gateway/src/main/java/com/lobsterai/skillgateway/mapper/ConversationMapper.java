package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    default List<Conversation> selectByUserIdOrderByUpdatedAt(String userId) {
        return selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getStatus, "active")
                .orderByDesc(Conversation::getUpdatedAt));
    }

    default Conversation selectByConversationId(String conversationId) {
        return selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationId, conversationId));
    }

    default List<Conversation> selectByApiKeyHash(String apiKeyHash) {
        return selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getApiKeyHash, apiKeyHash));
    }
}
