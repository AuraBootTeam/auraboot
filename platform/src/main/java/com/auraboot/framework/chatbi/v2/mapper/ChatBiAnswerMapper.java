package com.auraboot.framework.chatbi.v2.mapper;

import com.auraboot.framework.chatbi.v2.entity.ChatBiAnswer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@link ChatBiAnswer}. W3 will add list-by-user + list-by-model
 * helpers backing the /api/chatbi/history endpoint.
 */
@Mapper
public interface ChatBiAnswerMapper extends BaseMapper<ChatBiAnswer> {
}
