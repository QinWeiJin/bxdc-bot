package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.ConversationMessage;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /**
     * Cursor-based pagination: returns messages with created_at before the cursor,
     * ordered by created_at DESC (most recent first).
     *
     * @param conversationId target conversation
     * @param cursor         exclusive upper bound; null for latest messages
     * @param limit          max number of messages to return
     * @return messages ordered by created_at DESC
     */
    default List<ConversationMessage> selectByConversationIdCursor(
            String conversationId, LocalDateTime cursor, int limit) {
        LambdaQueryWrapper<ConversationMessage> wrapper = new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .orderByDesc(ConversationMessage::getCreatedAt)
                .orderByDesc(ConversationMessage::getId);
        if (cursor != null) {
            wrapper.lt(ConversationMessage::getCreatedAt, cursor);
        }
        wrapper.last("LIMIT " + limit);
        return selectList(wrapper);
    }

    default int deleteByConversationId(String conversationId) {
        return delete(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId));
    }

    /**
     * bxdcbot-multi-turn-async change：按 conversationId + parent_tool_id 查 BXDCBOT_RUN_RESULT 消息。
     * 用于 insertBxdcbotRunResult 幂等去重（同 run 多次调 complete 只写一次）。
     */
    default ConversationMessage findByParentToolId(String conversationId, String parentToolId) {
        List<ConversationMessage> list = selectList(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getParentToolId, parentToolId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}
