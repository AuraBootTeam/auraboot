package com.auraboot.framework.im.mapper;

import com.auraboot.framework.im.model.ImConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImConversationMapper extends BaseMapper<ImConversation> {

    /**
     * Atomically increment max_seq and return the new value.
     * Row-level lock ensures no gaps under concurrent writes.
     */
    @Update("""
        UPDATE ab_im_conversation
        SET max_seq = max_seq + 1,
            last_message_at = NOW(),
            updated_at = NOW()
        WHERE id = #{conversationId} AND tenant_id = #{tenantId}
        """)
    int incrementSeq(@Param("conversationId") Long conversationId,
                     @Param("tenantId") Long tenantId);
}
